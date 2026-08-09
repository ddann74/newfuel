package com.newfuel.fuelalert.settings

import android.content.SharedPreferences

/**
 * Minimal in-memory SharedPreferences fake for tests. Implements only
 * what SettingsRepository actually uses (String/Float/Boolean get/put,
 * edit(), apply()) - everything else throws, which is deliberate: this
 * is a narrow test double, not a general-purpose reimplementation, and
 * a test that exercised something beyond SettingsRepository's real
 * usage should fail loudly rather than silently succeed against an
 * untested code path.
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String> =
        throw UnsupportedOperationException("not used by SettingsRepository")
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var shouldClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            throw UnsupportedOperationException("not used by SettingsRepository")
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            apply { pending[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor =
            apply { pending[key!!] = REMOVE_MARKER }
        override fun clear(): SharedPreferences.Editor = apply { shouldClear = true }
        override fun commit(): Boolean {
            applyPending()
            return true
        }
        override fun apply() = applyPending()

        private fun applyPending() {
            if (shouldClear) values.clear()
            for ((key, value) in pending) {
                if (value === REMOVE_MARKER) values.remove(key) else values[key] = value
            }
        }
    }

    companion object {
        private val REMOVE_MARKER = Any()
    }
}
