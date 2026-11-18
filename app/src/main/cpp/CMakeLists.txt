cmake_minimum_required(VERSION 3.22.1)
project(whitevpn_antidpi C)

# Only the MIT-licensed native engines are imported; no GPL Android UI code.
set(BYEDPI_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/byedpi")
file(GLOB BYEDPI_SOURCES "${BYEDPI_DIR}/*.c")
list(REMOVE_ITEM BYEDPI_SOURCES "${BYEDPI_DIR}/win_service.c")

add_library(whitedpi SHARED ${BYEDPI_SOURCES} native_antidpi.c)
target_include_directories(whitedpi PRIVATE "${BYEDPI_DIR}")
target_compile_definitions(whitedpi PRIVATE ANDROID_APP)
target_compile_options(whitedpi PRIVATE -std=c99 -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused)
target_link_libraries(whitedpi PRIVATE android log dl)
target_link_options(whitedpi PRIVATE "-Wl,--wrap=daemon" "-Wl,-z,max-page-size=16384")
