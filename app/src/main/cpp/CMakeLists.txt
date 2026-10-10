cmake_minimum_required(VERSION 3.22.1)
project("meshcrypto" C)

add_library(meshcrypto SHARED native-crypto.c)
find_library(log-lib log)
target_link_libraries(meshcrypto ${log-lib})
