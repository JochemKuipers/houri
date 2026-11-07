import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import upscale.core.BackendPref
import upscale.core.ModelFamily
import upscale.core.ReaderKind
import upscale.core.SimpleAlgo
import upscale.core.UpscaleMode
import upscale.core.UpscalePreset
import upscale.identity.UpscaleIdentity
import upscale.policy.ModelCandidate
import upscale.policy.PolicyInput
import upscale.policy.UpscaleConfig
import upscale.policy.UpscalePlan
import upscale.policy.UpscalePolicy
import upscale.status.UpscaleStatusHub

/**
 * Pure-JVM assertions over the upscaling spine's three decision-bearing files: the identity
 * hashing (cache-key correctness), the policy planner (gate + ordering), and the status hub
 * (transition + totals). No Android, no JNI; the same sources the app runs are compiled here.
 */
fun main() {
    var passed = 0
    fun check(name: String, condition: Boolean) {
        if (!condition) {
            System.err.println("FAIL: $name")
            kotlin.system.exitProcess(1)
        }
        passed++
    }

    // ---- effectiveScale: clamping and preset adjustment ----
    check("factor 2/BALANCED is 2x", UpscalePolicy.effectiveScale(2f, UpscalePreset.BALANCED) == 2f)
    check("FAST pins 1.5x", UpscalePolicy.effectiveScale(4f, UpscalePreset.FAST) == 1.5f)
    check("HIGH multiplies 2x by 1.2", UpscalePolicy.effectiveScale(2f, UpscalePreset.HIGH) == 2.4f)
    check("HIGH clamps at 4x", UpscalePolicy.effectiveScale(4f, UpscalePreset.HIGH) == 4f)
    check("near-identity is 0", UpscalePolicy.effectiveScale(1.005f, UpscalePreset.BALANCED) == 0f)
    check("NaN falls back to 2x", UpscalePolicy.effectiveScale(Float.NaN, UpscalePreset.BALANCED) == 2f)
    check("over-4 clamps to 4x", UpscalePolicy.effectiveScale(9f, UpscalePreset.BALANCED) == 4f)

    // ---- plan: gates ----
    fun input(
        readerKind: ReaderKind = ReaderKind.PAGER,
        animated: Boolean = false,
        bytes: ByteArray = ByteArray(1024),
        width: Int = 1000,
        height: Int = 1500,
        native: Boolean = true,
        vulkan: Boolean = true,
        npu: Boolean = false,
        models: List<ModelCandidate> = listOf(ModelCandidate("realcugan-2x", 2, "NCNN")),
        ready: (String) -> Boolean = { true },
    ) = PolicyInput(
        readerKind = readerKind,
        isAnimated = animated,
        sourceBytes = bytes,
        sourceWidth = width,
        sourceHeight = height,
        targetScale = 2f,
        nativeAvailable = native,
        vulkanAvailable = vulkan,
        npuAvailable = npu,
        modelsFor = { _, _ -> models },
        modelReady = ready,
    )

    val disabled = UpscaleConfig(enabled = false)
    val disabledPlan = UpscalePolicy.plan(disabled, input())
    check("disabled plan is empty", disabledPlan.isEmpty)
    check("disabled plan carries the reason", disabledPlan.skipReason == "upscaling disabled")

    val enabledConfig = UpscaleConfig(enabled = true)
    val animatedPlan = UpscalePolicy.plan(enabledConfig, input(animated = true))
    check("animated images are skipped", animatedPlan.isEmpty && animatedPlan.skipReason == "animated image")

    val nearIdentity = UpscaleConfig(enabled = true, factor = 1.005f)
    check("near-identity skipped", UpscalePolicy.plan(nearIdentity, input()).skipReason == "near-identity scale")

    val hugePlan = UpscalePolicy.plan(enabledConfig, input(bytes = ByteArray(UpscalePolicy.MAX_INPUT_BYTES + 1)))
    check("byte cap skipped", hugePlan.skipReason == "bytes over cap")

    // ---- plan: mode composition ----
    val simple = UpscalePolicy.plan(UpscaleConfig(enabled = true, mode = UpscaleMode.SIMPLE), input())
    check("SIMPLE is one resample step", simple.steps.single() is UpscalePlan.Step.Simple)

    val auto = UpscalePolicy.plan(
        UpscaleConfig(enabled = true, mode = UpscaleMode.AUTO, backendPref = BackendPref.AUTO),
        input(),
    )
    check("AUTO starts with a native step", auto.steps.first() is UpscalePlan.Step.Native)
    check("AUTO ends with the resample fallback", auto.steps.last() is UpscalePlan.Step.Simple)

    val nativeOnly = UpscalePolicy.plan(
        UpscaleConfig(enabled = true, mode = UpscaleMode.NATIVE, backendPref = BackendPref.AUTO),
        input(),
    )
    check("NATIVE has no simple fallback", nativeOnly.steps.all { it is UpscalePlan.Step.Native })

    // ---- plan: backend ordering and probes ----
    val noVulkan = UpscalePolicy.plan(
        UpscaleConfig(enabled = true, mode = UpscaleMode.NATIVE, backendPref = BackendPref.AUTO),
        input(vulkan = false),
    )
    val noVulkanBackends = noVulkan.steps.filterIsInstance<UpscalePlan.Step.Native>().map { it.backendPref }
    check("no-vulkan plan skips VULKAN", BackendPref.VULKAN !in noVulkanBackends)
    check("no-vulkan plan keeps CPU", BackendPref.CPU in noVulkanBackends)

    val npuPlan = UpscalePolicy.plan(
        UpscaleConfig(enabled = true, mode = UpscaleMode.NATIVE, backendPref = BackendPref.NPU),
        input(
            npu = true,
            models = listOf(ModelCandidate("realcugan-2x", 2, "ONNX")),
        ),
    )
    check(
        "NPU resolves the ONNX candidate",
        (npuPlan.steps.first() as UpscalePlan.Step.Native).format == "ONNX",
    )

    val notReady = UpscalePolicy.plan(
        UpscaleConfig(enabled = true, mode = UpscaleMode.NATIVE, backendPref = BackendPref.AUTO),
        input(ready = { false }),
    )
    check("not-ready models leave no native steps", notReady.steps.isEmpty())
    check(
        "NATIVE with no native runtime is skipped up front",
        UpscalePolicy.plan(UpscaleConfig(enabled = true, mode = UpscaleMode.NATIVE), input(native = false)).skipReason ==
            "no native runtime",
    )
    check(
        "AUTO with no native runtime falls through to the resample",
        UpscalePolicy.plan(UpscaleConfig(enabled = true, mode = UpscaleMode.AUTO), input(native = false)).steps ==
            listOf(UpscalePlan.Step.Simple(SimpleAlgo.BICUBIC)),
    )

    // ---- identity: cache keys ----
    val a = "page-a".toByteArray()
    val b = "page-b".toByteArray()
    val keyA = UpscaleIdentity.nativeKey(a, 2f, "realcugan-2x", "BALANCED", "VULKAN", "NCNN", listOf("p:abc", "b:def"))
    val keyA2 = UpscaleIdentity.nativeKey(a, 2f, "realcugan-2x", "BALANCED", "VULKAN", "NCNN", listOf("p:abc", "b:def"))
    val keyB = UpscaleIdentity.nativeKey(b, 2f, "realcugan-2x", "BALANCED", "VULKAN", "NCNN", listOf("p:abc", "b:def"))
    val keyModel = UpscaleIdentity.nativeKey(a, 2f, "realesrgan-2x", "BALANCED", "VULKAN", "NCNN", listOf("p:abc", "b:def"))
    val keyArtifacts = UpscaleIdentity.nativeKey(a, 2f, "realcugan-2x", "BALANCED", "VULKAN", "NCNN", listOf("p:abc", "b:dead"))
    val keyFactor = UpscaleIdentity.nativeKey(a, 3f, "realcugan-2x", "BALANCED", "VULKAN", "NCNN", listOf("p:abc", "b:def"))
    check("identity is deterministic", keyA == keyA2)
    check("different bytes → different key", keyA != keyB)
    check("different model → different key", keyA != keyModel)
    check("different artifact hash → different key", keyA != keyArtifacts)
    check("different factor → different key", keyA != keyFactor)

    val simpleKey = UpscaleIdentity.simpleKey(a, 2f, "BICUBIC", "SIMPLE")
    val simpleKeyAlgo = UpscaleIdentity.simpleKey(a, 2f, "NEAREST", "SIMPLE")
    val simpleKeySame = UpscaleIdentity.simpleKey(a, 2f, "BICUBIC", "SIMPLE")
    check("simple key deterministic", simpleKey == simpleKeySame)
    check("algo changes the simple key", simpleKey != simpleKeyAlgo)
    check("native and simple key spaces do not collide", UpscaleIdentity.simpleKey(a, 2f, "BICUBIC", "SIMPLE") != keyA)
    check("keys are hex digests", keyA.length == 64 && keyA.all { it.isDigit() || it in 'a'..'f' })

    // ---- status hub: transitions, events, totals ----
    runBlocking {
        val hub = UpscaleStatusHub()
        var served: UpscaleStatusHub.Event.Served? = null
        // Subscribe first and yield, so the SharedFlow collector is attached before the
        // event fires — SharedFlow has no replay, and a later subscribe would miss it.
        val collector = launch {
            hub.events.collect { event ->
                if (event is UpscaleStatusHub.Event.Served) served = event
            }
        }
        kotlinx.coroutines.yield()

        hub.markRunning(1L, 2L, 3, "ncnn")
        val runningChapter = hub.chapters.value[1L to 2L]
        check("running marks the page", runningChapter?.pages?.get(3)?.state == UpscaleStatusHub.PageState.RUNNING)

        hub.markDone(1L, 2L, 3, "ncnn", "realcugan-2x", elapsedMs = 42, fromCache = false)
        // The controller, not the hub, emits Served events — mirror it here so the event
        // subscriber path is exercised too.
        hub.tryEmit(
            UpscaleStatusHub.Event.Served(
                mangaId = 1L, chapterId = 2L, pageIndex = 3, backendId = "ncnn",
                modelId = "realcugan-2x", cacheKey = "key", elapsedMs = 42, prefetched = false,
            ),
        )
        val doneChapter = hub.chapters.value[1L to 2L]
        val donePage = doneChapter?.pages?.get(3)
        check("done page is Done", donePage?.state == UpscaleStatusHub.PageState.DONE)
        check("done records model", donePage?.modelId == "realcugan-2x")
        check("done records elapsed", donePage?.elapsedMs == 42L)

        hub.markFellBack(1L, 2L, 4, "ncnn", "ort", "backend not ready")
        hub.markFellBack(1L, 2L, 4, "ort", "simple", "backend not ready")
        hub.markFailed(1L, 2L, 5, "ort", "every step failed")

        val totals = hub.totals.value
        check("served counted", totals.served == 1)
        check("servedFromCache not counted as inference", totals.servedFromCache == 0)
        check("fallbacks counted", totals.fellBack == 2)
        check("failures counted", totals.failed == 1)
        check("inference time recorded", totals.totalInferenceMs >= 42L)

        // The emission resumes the suspended collector on this dispatcher's queue; a yield
        // lets it run and record the event before we cancel it.
        kotlinx.coroutines.yield()
        collector.cancel()
        check("Served event carries page and model", served?.mangaId == 1L && served?.modelId == "realcugan-2x")
        check("Served event is a display serve", served?.prefetched == false)
        check("Served event carries the cache key", served?.cacheKey == "key")
        check("Served event carries elapsed", served?.elapsedMs == 42L)

        hub.clear()
        check("clear resets totals", hub.totals.value.served == 0)
        check("clear resets chapters", hub.chapters.value.isEmpty())
    }

    println("UpscaleSpineHarness: $passed assertions passed")
}
