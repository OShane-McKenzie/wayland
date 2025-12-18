cmake_minimum_required(VERSION 3.16)
project(wayland_helper C)

set(CMAKE_C_STANDARD 11)

# ── Dependencies ──────────────────────────────────────────────────────────────
find_package(PkgConfig REQUIRED)
pkg_check_modules(WAYLAND_CLIENT REQUIRED wayland-client)
find_program(WAYLAND_SCANNER wayland-scanner REQUIRED)

# ── Generate wlr-layer-shell protocol glue code ───────────────────────────────
set(LAYER_XML    "${CMAKE_CURRENT_SOURCE_DIR}/protocol/wlr-layer-shell-unstable-v1.xml")
set(LAYER_HEADER "${CMAKE_CURRENT_BINARY_DIR}/wlr-layer-shell-client-protocol.h")
set(LAYER_SRC    "${CMAKE_CURRENT_BINARY_DIR}/wlr-layer-shell-protocol.c")

add_custom_command(
    OUTPUT ${LAYER_HEADER}
    COMMAND ${WAYLAND_SCANNER} client-header ${LAYER_XML} ${LAYER_HEADER}
    DEPENDS ${LAYER_XML}
    COMMENT "Generating wlr-layer-shell header"
)

add_custom_command(
    OUTPUT ${LAYER_SRC}
    COMMAND ${WAYLAND_SCANNER} private-code ${LAYER_XML} ${LAYER_SRC}
    DEPENDS ${LAYER_XML} ${LAYER_HEADER}
    COMMENT "Generating wlr-layer-shell source"
)

# ── Generate xdg-shell protocol glue code ─────────────────────────────────────
set(XDG_XML    "/usr/share/wayland-protocols/stable/xdg-shell/xdg-shell.xml")

set(XDG_HEADER "${CMAKE_CURRENT_BINARY_DIR}/xdg-shell-protocol.h")
set(XDG_SRC    "${CMAKE_CURRENT_BINARY_DIR}/xdg-shell-protocol.c")

add_custom_command(
    OUTPUT ${XDG_HEADER}
    COMMAND ${WAYLAND_SCANNER} client-header ${XDG_XML} ${XDG_HEADER}
    DEPENDS ${XDG_XML}
    COMMENT "Generating xdg-shell header"
)

add_custom_command(
    OUTPUT ${XDG_SRC}
    COMMAND ${WAYLAND_SCANNER} private-code ${XDG_XML} ${XDG_SRC}
    DEPENDS ${XDG_XML} ${XDG_HEADER}
    COMMENT "Generating xdg-shell source"
)

# Force generation to happen before building the executable
add_custom_target(wayland-protocols
    DEPENDS ${LAYER_HEADER} ${LAYER_SRC} ${XDG_HEADER} ${XDG_SRC}
)

# ── Binary ────────────────────────────────────────────────────────────────────
add_executable(wayland-helper
    wayland_helper.c
    ${LAYER_SRC}
    ${XDG_SRC}
)

add_dependencies(wayland-helper wayland-protocols)

target_include_directories(wayland-helper PRIVATE
    ${WAYLAND_CLIENT_INCLUDE_DIRS}
    ${CMAKE_CURRENT_BINARY_DIR}
)

target_link_libraries(wayland-helper
    ${WAYLAND_CLIENT_LIBRARIES}
)

target_compile_options(wayland-helper PRIVATE
    ${WAYLAND_CLIENT_CFLAGS_OTHER}
    -Wall -Wextra -O2
)

# ── Install / copy to Gradle resources ────────────────────────────────────────
execute_process(
    COMMAND uname -m
    OUTPUT_VARIABLE HOST_ARCH
    OUTPUT_STRIP_TRAILING_WHITESPACE
)

string(TOLOWER "${HOST_ARCH}" HOST_ARCH)
if(HOST_ARCH STREQUAL "arm64")
    set(HOST_ARCH "aarch64")
endif()

set(RESOURCES_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../src/jvmMain/resources/native/${HOST_ARCH}"
)

install(TARGETS wayland-helper
    RUNTIME DESTINATION "${RESOURCES_DIR}"
    PERMISSIONS OWNER_EXECUTE OWNER_WRITE OWNER_READ
                GROUP_EXECUTE GROUP_READ
                WORLD_EXECUTE WORLD_READ
)

add_custom_target(deploy
    COMMAND ${CMAKE_COMMAND} --install . --prefix ""
    DEPENDS wayland-helper
    COMMENT "Copying wayland-helper to Gradle resources (${HOST_ARCH})"
)
