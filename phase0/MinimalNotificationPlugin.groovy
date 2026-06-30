import com.dtolabs.rundeck.plugins.notification.NotificationPlugin

rundeckPlugin(NotificationPlugin) {
    title = "Phase0 Minimal Notification"
    description = "Minimal test plugin for Rundeck 5.9 load diagnosis"

    onsuccess {
        println("phase0-minimal: success execution=${execution?.id}")
        true
    }
}
