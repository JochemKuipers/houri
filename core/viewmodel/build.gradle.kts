plugins {
    id("mihon.library")
}

android {
    namespace = "mihon.core.viewmodel"
}

dependencies {
    api(projects.core.concurrency)
    api(androidx.lifecycle.viewmodel)
}
