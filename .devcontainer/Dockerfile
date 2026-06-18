# Custom Mindustry client (Foo's client v8) dev image.
# Build requires JDK 17 EXACTLY (settings.gradle enforces it).
FROM mcr.microsoft.com/devcontainers/java:17-bookworm

# Runtime libraries needed by Arc's SDL2 backend (arc.backend.sdl.SdlApplication)
# to open a window + GL context. Covers BOTH driver paths:
#   SDL_VIDEODRIVER=x11      -> libx11/xext/xcursor/xi/xrandr/xss (works via XWayland)
#   SDL_VIDEODRIVER=wayland  -> libwayland-*/libdecor/libxkbcommon
# Plus Mesa GL/EGL (paired with --device=/dev/dri) and ALSA/Pulse for audio.
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get install -y --no-install-recommends \
        libgl1 libglu1-mesa libegl1 libgles2 mesa-utils \
        libxkbcommon0 libxkbcommon-x11-0 \
        libx11-6 libxext6 libxcursor1 libxi6 libxrandr2 libxinerama1 libxss1 libxxf86vm1 \
        libwayland-client0 libwayland-egl1 libwayland-cursor0 libdecor-0-0 \
        libasound2 libpulse0 libopenal1 \
        x11-utils \
    && apt-get clean && rm -rf /var/lib/apt/lists/*
