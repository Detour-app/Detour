# Keep rules for the release (minified) build type. MapLibre and Play
# Services ship their own consumer-proguard-rules.pro bundled in their AARs,
# so this file starts empty; add a rule here only when R8 actually fails on
# a specific library, and name that library in a one-line comment.

# Ktor (via :shared) drags in slf4j-api, whose LoggerFactory reflects at
# org.slf4j.impl.StaticLoggerBinder — a class only a logging backend supplies,
# and there is none on Android. R8 fails the build on the dangling reference;
# the code path is never taken because nothing calls slf4j here.
-dontwarn org.slf4j.**
