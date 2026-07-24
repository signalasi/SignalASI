package com.signalasi.chat

internal object DebugIntentExtras {
    val oneShotKeys = listOf(
        "signalasi_debug_approve_friend",
        "signalasi_debug_auto_confirm_scan",
        "signalasi_debug_backup_roundtrip",
        "signalasi_debug_cloud_models_roundtrip",
        "signalasi_debug_chat_history_probe_b64",
        "signalasi_debug_control_center_page",
        "signalasi_debug_control_center_roundtrip",
        "signalasi_debug_control_center_theme",
        "signalasi_debug_delete_contact",
        "signalasi_debug_destroy_all_data",
        "signalasi_debug_home_assistant_url",
        "signalasi_debug_incoming",
        "signalasi_debug_incoming_b64",
        "signalasi_debug_open_advanced_options",
        "signalasi_debug_open_agents",
        "signalasi_debug_open_automation",
        "signalasi_debug_open_backup_export",
        "signalasi_debug_open_backup_import",
        "signalasi_debug_open_cloud_provider",
        "signalasi_debug_open_cloud_providers",
        "signalasi_debug_open_cloud_switch_provider",
        "signalasi_debug_open_contact",
        "signalasi_debug_open_contact_detail",
        "signalasi_debug_open_contacts",
        "signalasi_debug_open_create_group",
        "signalasi_debug_open_destroy_data",
        "signalasi_debug_open_device",
        "signalasi_debug_open_group",
        "signalasi_debug_open_language_settings",
        "signalasi_debug_open_local_model",
        "signalasi_debug_open_messages",
        "signalasi_debug_open_new_friends",
        "signalasi_debug_open_on_device_agent",
        "signalasi_debug_open_protocol_quality",
        "signalasi_debug_open_recent_tasks",
        "signalasi_debug_open_security",
        "signalasi_debug_open_signal_link_protocol",
        "signalasi_debug_open_voice",
        "signalasi_debug_open_voice_settings",
        "signalasi_debug_pairing",
        "signalasi_debug_rename_contact",
        "signalasi_debug_rename_name",
        "signalasi_debug_rename_name_b64",
        "signalasi_debug_revoke",
        "signalasi_debug_scan_payload",
        "signalasi_debug_scan_payload_b64",
        "signalasi_debug_seed_cloud_provider",
        "signalasi_debug_status",
        "signalasi_debug_voice_settings_roundtrip"
    )

    fun consume(removeExtra: (String) -> Unit) {
        oneShotKeys.forEach(removeExtra)
    }
}
