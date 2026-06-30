import com.dtolabs.rundeck.plugins.notification.NotificationPlugin
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.text.SimpleTemplateEngine

// Truncate a string to a specified number of bytes.
def substringByBytes(String text, int byte_size, String charset)
{
    String ret = ""
    log_snipped = false

    try {
        int text_byte_cnt = 0
        for (int i = 0; i < text.length(); i++) {
            String tmp_text = text.substring(i, i + 1)
            byte[] tmp_text_byte = tmp_text.getBytes(charset)

            if (text_byte_cnt + tmp_text_byte.length > byte_size) {
                log_snipped = true
                return [ret, log_snipped]
            } else {
                ret = ret + tmp_text
                text_byte_cnt = text_byte_cnt + tmp_text_byte.length
            }
        }
    } catch (Exception ex) {
        ex.printStackTrace()
    }
    return [ret, log_snipped]
}

rundeckPlugin(NotificationPlugin) {
    title = "Microsoft Teams notification with log output"
    description = "Allows to set up notification for Microsoft Teams chats for a channel, via Webhook URL. To use it you will have to obtain webhook for your channel first and setit up."

    ASSET_DIR = "/var/lib/rundeck/libext/MicrosoftTeamsNotificationWithLog.d"
    PAYLOAD_TEMPLATE_DIR = "${ASSET_DIR}/templates"
    IMAGE_DIR = "${ASSET_DIR}/images"
    INFORMATION_ICON_FILE = "information-icon.png"
    ALERT_ICON_FILE = "alert-icon.png"

    MAX_LOG_SIZE_KB = 11
    DEFAULT_API_URL = "https://localhost:4443/api"
    DEFAULT_API_VERSION = "31"

    configuration {
        webhook_url title:"Webhook URL", required:true, type:"String", description:"You may find it in Microsoft Teams Channel user interfaces by using Incomming Webhook connector via:  Channel Name -> Connectors -> Incomming Webhook"
        rundeck_authtoken title:"Rundeck User API token", required:true, type:"String", description:"Rundeck API token. via: Account -> profile -> User API Tokens."
        include_outputlog title:"Include job output as inline message", required:true, defaultValue:"true", values:["true", "false"], description:"Include job output logs in messages. Limit the size of the log to ${this.MAX_LOG_SIZE_KB}k bytes."
        template_name title:"Message template", required:true, defaultValue:"SimpleMessage-AdaptiveCard", description:"Specifies the template for the notification message."
        template_language title:"Template language", required:true, defaultValue:"ja", values:["en", "ja"], description:"Specifies the language of the template for the notification message."
    }

    escapeJson = { String text ->
        text = text.replaceAll(/\r/, '<<CR>>')
        text = text.replaceAll(/\n/, '<<LF>>')
        //text = text.replaceAll(/\\/, '/')
        text = text.replace('\\', '/')
        text = text.replaceAll(/\"/, '\\\\"')
        text = text.replaceAll(/\//, '\\\\/')
        //text = text.replaceAll("=", '\\=')
        //text = text.replaceAll("-", '\\-')
        text = text.replaceAll(/\h/, '&emsp;')
        text = text.replaceAll(/<<CR>><<LF>>/, '\\\\n')
        text = text.replaceAll(/<<CR>>/, '\\\\n')
        text = text.replaceAll(/<<LF>>/, '\\\\n')
        // text = URLEncoder.encode(text, "UTF-8")
        return text
    }

    // Use the Rundeck API to get the output of a job.
    getRundeckLog = { execution, configuration ->
        def default_api_url = this.DEFAULT_API_URL
        def default_api_version = this.DEFAULT_API_VERSION
        def curl_script = '''
            API_URL=""
            if [ -f /home/rundeck/etc/framework.properties ]; then
              SERVER_URL=$(grep -E '^framework\\.server\\.url' /home/rundeck/etc/framework.properties | head -1 | cut -d= -f2- | tr -d ' \\r')
              if [ -n "$SERVER_URL" ]; then
                API_URL="${SERVER_URL}/api"
              fi
            fi
            if [ -z "$API_URL" ] && [ -f /etc/rundeck/framework.properties ]; then
              SERVER_URL=$(grep -E '^framework\\.server\\.url' /etc/rundeck/framework.properties | head -1 | cut -d= -f2- | tr -d ' \\r')
              if [ -n "$SERVER_URL" ]; then
                API_URL="${SERVER_URL}/api"
              fi
            fi
            API_URL="${API_URL:-''' + default_api_url + '''}"
            API_VER=$(curl -sk "${API_URL}/99/system/info" 2>/dev/null | sed -n 's/.*"apiversion"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p' | head -1)
            API_VER="${API_VER:-''' + default_api_version + '''}"
            curl -s -k -H "Accept: application/json" -H "X-Rundeck-Auth-Token: ''' + configuration.rundeck_authtoken + '''" -X GET "${API_URL}/${API_VER}/execution/''' + execution.id + '''/output"
        '''.stripIndent().trim()
        def proc = [ 'bash', '-c', curl_script ].execute()
        proc.waitFor()
        def rundeck_log = proc.text
        if (proc.exitValue() != 0) {
            throw new IOException("Rundeck API request failed (exit ${proc.exitValue()}): ${rundeck_log}")
        }
        if (!rundeck_log?.trim()) {
            throw new IOException("Rundeck API returned empty response for execution ${execution.id}")
        }
        def json_obj = (new JsonSlurper()).parseText(rundeck_log)
        if (!json_obj?.entries) {
            throw new IOException("Rundeck API response missing entries for execution ${execution.id}")
        }
        String job_log = json_obj['entries']['log'].join("\n")

        // Due to Teams specifications, maximum message size is 25k bytes.
        // Limit the size of the log to 20k bytes.
        // Allow up to "MAX_LOG_SIZE_KB" kbytes (default 14kbytes) to avoid size bloat due to encoding.
        (job_log, log_snipped) = substringByBytes(job_log, this.MAX_LOG_SIZE_KB * 1024, 'UTF-8')
        job_log = "\n" + job_log
        job_log = this.escapeJson(job_log)
        if (log_snipped) {
            job_log += "\n<<SNIPPED>>"
        }
        job_log += "\n"
        return [job_log, log_snipped]
    }

    // Use the webhook to send the Teams message.
    sendTeamsNotification = { execution, configuration, template_file, template_args ->
        try {
            def payload_template = new File(this.PAYLOAD_TEMPLATE_DIR + "/" + template_file).text
            String payload_string = (new SimpleTemplateEngine()).createTemplate(payload_template).make(template_args)
            def json_obj = (new JsonSlurper()).parseText(payload_string)
            String json_payload = JsonOutput.toJson(json_obj)
            String paylod_file_name = "/tmp/json_payload-${execution.id}.txt"
            def paylod_file = new File(paylod_file_name)
            paylod_file.text = json_payload
            def proc = [ 'bash', '-c', "curl -v -k -X POST -H \"Content-Type: application/json\" -d @${paylod_file_name} '${configuration.webhook_url}'" ].execute()
            proc.waitFor()
            def output = proc.text
            paylod_file.delete()
            if (proc.exitValue() != 0) {
                println("MicrosoftTeamsNotificationWithLog: webhook POST failed for execution ${execution.id}, exit=${proc.exitValue()}, output=${output}")
                return false
            }
            return true
        } catch (Exception ex) {
            println("MicrosoftTeamsNotificationWithLog: webhook POST error for execution ${execution.id}: ${ex.message}")
            ex.printStackTrace()
            return false
        }
    }

    handleTrigger = { execution, configuration, notification_label, color ->
        boolean include_log_in_message = (configuration.include_outputlog == "true")

        if (notification_label == "START") {
            (job_log, log_snipped) = ["", false]
        } else if (include_log_in_message) {
            try {
                (job_log, log_snipped) = this.getRundeckLog(execution, configuration)
            } catch (Exception ex) {
                println("MicrosoftTeamsNotificationWithLog: failed to fetch execution log for ${execution.id}, falling back to nolog template: ${ex.message}")
                ex.printStackTrace()
                include_log_in_message = false
                (job_log, log_snipped) = ["", false]
            }
        } else {
            (job_log, log_snipped) = ["", false]
        }

        if (notification_label == "START") {
            icon_base64 = new File(this.IMAGE_DIR + "/" + this.INFORMATION_ICON_FILE).bytes.encodeBase64().toString()
        } else if (notification_label == "NG") {
            icon_base64 = new File(this.IMAGE_DIR + "/" + this.ALERT_ICON_FILE).bytes.encodeBase64().toString()
        } else if (notification_label == "OK") {
            icon_base64 = new File(this.IMAGE_DIR + "/" + this.INFORMATION_ICON_FILE).bytes.encodeBase64().toString()
        }

        String template_file
        if (include_log_in_message) {
            template_file = "${configuration.template_name}-${configuration.template_language}.template"
        } else {
            template_file = "${configuration.template_name}-nolog-${configuration.template_language}.template"
        }

        // String job_log_line_fix = job_log.replace("\n", "\n\n")
        String job_log_line_fix = job_log.replaceAll(/\h/, '&emsp;').replace("\\n", "  \n").replace("\\r", "  \n")
        template_args = [
            "job_log":job_log,
            "job_log_line_fix":job_log_line_fix,
            "notification_label":notification_label,
            "color":color,
            "log_snipped":log_snipped,
            "icon_base64":icon_base64 ]
        template_args.putAll(["execution":execution])
        return this.sendTeamsNotification(execution, configuration, template_file, template_args)
    }

    onstart {
        type = "START"
        color = "696969"
        notification_label = "START"
        return this.handleTrigger(execution, configuration, notification_label, color)
    }

    onfailure {
        type = "FAILURE"
        color = "E81123"
        notification_label = "NG"
        return this.handleTrigger(execution, configuration, notification_label, color)
    }

    onsuccess {
        type = "SUCCESS"
        color = "228B22"
        notification_label = "OK"
        return this.handleTrigger(execution, configuration, notification_label, color)
    }
}
