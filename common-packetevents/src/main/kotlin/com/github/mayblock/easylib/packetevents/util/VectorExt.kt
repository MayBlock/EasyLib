package com.github.mayblock.easylib.packetevents.util

import com.github.mayblock.easylib.api.util.Vector
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.util.Vector3i

fun Vector<Int>.toVector3i() = Vector3i(x, y, z)
fun Vector<Double>.toVector3d() = Vector3d(x, y, z)
fun Vector<Float>.toVector3f() = Vector3f(x, y, z)