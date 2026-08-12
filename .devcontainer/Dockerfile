# One toolchain for the whole repository: the .NET backend under backend/ and
# the Gradle/Kotlin Multiplatform client under app/, wear/ and shared/.
#
# The base is Microsoft's .NET devcontainer image rather than a bare Debian,
# because backend/ is now the server (it replaced the Python one) and that image
# already carries the SDK, a non-root `vscode` user at 1000:1000, and the
# devcontainer plumbing. The Android half is layered on top.
#
# Pinned to what the builds actually ask for:
#   .NET 10        backend/Directory.Build.props (<TargetFramework>net10.0)
#   JDK 17         app/build.gradle.kts (source/targetCompatibility)
#   compileSdk 35  app/build.gradle.kts, wear/build.gradle.kts
#   AGP 8.5.2 / Kotlin 2.0.20 / Gradle 8.9 — all satisfied by JDK 17.
# The host cannot build either half: it has only java-26-openjdk, which Gradle
# 8.9 rejects outright, and /opt/android-sdk with platform-tools and nothing else.
FROM mcr.microsoft.com/devcontainers/dotnet:2-10.0-noble

# The image already defines vscode at 1000:1000, which matches the host user, so
# files written into the bind-mounted workspace stay owned by them. Named here
# rather than hard-coded further down.
ARG USER=vscode
ARG UID=1000
ARG GID=1000

# The Android SDK command-line tools. Version-pinned so a rebuild produces the
# same sdkmanager; the packages it then installs are pinned separately below.
ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV DEBIAN_FRONTEND=noninteractive

# git, curl, unzip and sudo already ship in the base image; only the JDK is
# missing. Docker CLI is deliberately absent too — the docker-outside-of-docker
# feature in devcontainer.json installs it and wires up the host socket.
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jdk \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Straight from Google rather than a distro `sdkmanager` package: Ubuntu noble
# does not package one, and the zip is the same artefact Android Studio uses.
# It must live at cmdline-tools/latest/ — sdkmanager derives the SDK root from
# its own path and refuses to run from anywhere else.
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
    && mv /tmp/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools

ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin"

# Licences are accepted at image-build time so a fresh container can compile
# without an interactive prompt. `yes |` exits non-zero on SIGPIPE once
# sdkmanager stops reading, which is not a failure.
RUN (yes | sdkmanager --licenses > /dev/null) || true

RUN sdkmanager \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" \
    && chown -R ${UID}:${GID} ${ANDROID_HOME}

# On PATH via ENV rather than only ~/.bashrc: `docker exec … <cmd>` runs a
# non-login, non-interactive shell that never sources bashrc, so adb would
# otherwise need `bash -ic` to be found.
ENV PATH="${PATH}:${ANDROID_HOME}/platform-tools"

USER ${USER}

# Kept as well, for an interactive shell opened inside the container.
RUN echo "export ANDROID_HOME=${ANDROID_HOME}" >> ~/.bashrc \
    && echo "export ANDROID_SDK_ROOT=${ANDROID_HOME}" >> ~/.bashrc \
    && echo "export JAVA_HOME=${JAVA_HOME}" >> ~/.bashrc \
    && echo 'export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"' >> ~/.bashrc
