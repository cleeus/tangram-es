check_unsupported_compiler_version()

add_definitions(-DTANGRAM_OSX)

find_package(OpenGL REQUIRED)

include(cmake/glfw.cmake)

add_bundle_resources(RESOURCES "${PROJECT_SOURCE_DIR}/scenes" "Resources")

add_executable(tangram
  MACOSX_BUNDLE
  platforms/osx/src/main.mm
  platforms/osx/src/osxPlatform.mm
  platforms/common/platform_gl.cpp
  platforms/common/glfwApp.cpp
  platforms/common/appleAllowedFonts.mm
  ${RESOURCES}
)

target_include_directories(tangram
  PRIVATE
  platforms/common
)

target_link_libraries(tangram
  PRIVATE
  tangram-core
  glfw
  ${GLFW_LIBRARIES}
  ${OPENGL_LIBRARIES}
)

target_compile_options(tangram
  PRIVATE
  -std=c++1y
  -stdlib=libc++
  -Wall
  -Wreturn-type
  -Wsign-compare
  -Wignored-qualifiers
  -Wtype-limits
  -Wmissing-field-initializers
)

# add resource files and property list
set_target_properties(tangram PROPERTIES MACOSX_BUNDLE_INFO_PLIST "${PROJECT_SOURCE_DIR}/platforms/osx/Info.plist")
