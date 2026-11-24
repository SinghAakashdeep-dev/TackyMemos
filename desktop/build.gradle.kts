plugins {
    kotlin("jvm") version "2.0.21"
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.json:json:20240303")
}

// One self-contained jar (app + Kotlin runtime + org.json) in dist/, started with javaw.exe.
// A plain jar runs under Windows Smart App Control, which blocks unsigned .exe/.dll files.
tasks.jar {
    archiveFileName.set("TackyMemos.jar")
    destinationDirectory.set(layout.projectDirectory.dir("dist"))
    manifest { attributes("Main-Class" to "stickynotes.MainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    }
}
