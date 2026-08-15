plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The INDEPENDENT provider harness (#6, §10.1 lane `sol-blackbox`).
//
// This module implements the provider side of Environment Control v1 from the
// FROZEN CONTRACT TEXT ALONE. It deliberately shares no code with
// apps/qianwangyou's production provider — the whole point (#6 "Why") is that
// product implementations cannot prove their own recovery claims, so the
// scenarios drive an implementation whose only common ancestor with the
// product is the contract itself. Divergence between the two under the same
// scenario is a contract-ambiguity finding, not a bug in either.
android {
    namespace = "io.github.terryyyc.fakexxx.acceptance.fakeqwy"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":environment-control-v1"))
}
