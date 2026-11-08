cmake_minimum_required(VERSION 3.22)
project(sharpfocus C)

add_library(fcam SHARED fcam.c)
target_compile_options(fcam PRIVATE -O2 -Wall)
