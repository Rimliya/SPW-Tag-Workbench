// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

// 编译期占位，不是运行期实现。
//
// 宿主 com.xuncorp.voxzen.ui.navigation.AppNavigationKt.AppNavigation(Composer, Int)
// 没有业务参数，所以插件可以反过来调用它 —— 这是「测量但不放置」宿主内容、
// 让窗口 DWM 材质裸露出来的前提。
//
// 两点必须成立，否则调用点会失效：
//  1. 文件类名必须是 AppNavigationKt（用 @file:JvmName），与宿主一致；
//     否则调用点会指向本 stub 的类名，运行期解析不到宿主实现。
//  2. body 不能是空的。Compose 编译器会把空组件整体优化掉，连调用都不生成，
//     于是宿主内容压根不会被组合。这里读一个组合内的值，强制保留调用点。
//
// 本文件的产物只参与编译，不进插件 jar（独立 source set，compileOnly）。
@file:JvmName("AppNavigationKt")

package com.xuncorp.voxzen.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentCompositeKeyHash

@Composable
fun AppNavigation() {
    // 永不执行：运行期解析到宿主实现。读取组合内值只为避免被优化掉。
    currentCompositeKeyHash
}
