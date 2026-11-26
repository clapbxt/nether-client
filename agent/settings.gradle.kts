// The in-game client (Java agent). Built separately from the Rust workspace; crates/core/build.rs embeds the
// jars this build writes to agent/build/. See docs/develop/building.md.
rootProject.name = "nether-agent"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
