package com.livesub

/** حالة مشتركة بين الخدمة والواجهة */
object Bus {
    @Volatile var running = false
    @Volatile var level = 0f
    @Volatile var status = ""
    @Volatile var original = ""
    @Volatile var translated = ""
    @Volatile var stamp = 0L
}
