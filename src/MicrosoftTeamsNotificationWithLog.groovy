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
    RUNDECK_API_ENDPOINT = "https://localhost:4443/api"
    RUNDECK_API_VERSION = 31

    configuration {
        webhook_url title:"Webhook URL", required:true, type:"String", description:"You may find it in Microsoft Teams Channel user interfaces by using Incomming Webhook connector via:  Channel Name -> Connectors -> Incomming Webhook"
        rundeck_authtoken title:"Rundeck User API token", required:true, type:"String", description:"Rundeck API token. via: Account -> profile -> User API Tokens."
        include_outputlog title:"Include job output as inline message", required:true, defaultValue:"true", values:["true", "false"], description:"Include job output logs in messages. Limit the size of the log to ${this.MAX_LOG_SIZE_KB}k bytes."
        template_name title:"Message template", required:true, defaultValue:"SimpleMessage", description:"Specifies the template for the notification message."
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
        rundeck_log = [ 'bash', '-c', "curl -v -k -H \"Accept: application/json\" -H \"X-Rundeck-Auth-Token: ${configuration.rundeck_authtoken}\" -X GET ${this.RUNDECK_API_ENDPOINT}/${this.RUNDECK_API_VERSION}/execution/${execution.id}/output" ].execute().text
        def json_obj = (new JsonSlurper()).parseText(rundeck_log)
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
        //def job_log_file = new File("/tmp/job_log.txt")
        //job_log_file.text = job_log
        return [job_log, log_snipped]
    }

    // Use the webhook to send the Teams message.
    sendTeamsNotification = { execution, configuration, template_file, template_args ->
        def payload_template = new File(this.PAYLOAD_TEMPLATE_DIR + "/" + template_file).text
        String payload_string = (new SimpleTemplateEngine()).createTemplate(payload_template).make(template_args)
        def json_obj = (new JsonSlurper()).parseText(payload_string)
        String json_payload = JsonOutput.toJson(json_obj)
        String paylod_file_name = "/tmp/json_payload-${execution.id}.txt"
        def paylod_file = new File(paylod_file_name)
        paylod_file.text = json_payload
        process = [ 'bash', '-c', "curl -v -k -X POST -H \"Content-Type: application/json\" -d @${paylod_file_name} ${configuration.webhook_url}" ].execute().text
        paylod_file.delete()
    }

    handleTrigger = { execution, configuration, job_status, color ->
        if (job_status == "START") {
            (job_log, log_snipped) = ["", false]
        } else {
            if (configuration.include_outputlog == "true") {
                (job_log, log_snipped) = this.getRundeckLog(execution, configuration)
            } else {
                (job_log, log_snipped) = ["", false]
            }
        }

        if (job_status == "START") {
            icon_base64 = new File(this.IMAGE_DIR + "/" + this.INFORMATION_ICON_FILE).bytes.encodeBase64().toString()
        } else if (job_status == "NG") {
            icon_base64 = new File(this.IMAGE_DIR + "/" + this.ALERT_ICON_FILE).bytes.encodeBase64().toString()
        } else if (job_status == "OK") {
            icon_base64 = new File(this.IMAGE_DIR + "/" + this.INFORMATION_ICON_FILE).bytes.encodeBase64().toString()
        }

        if (configuration.include_outputlog == "true") {
            template_file = "${configuration.template_name}-${configuration.template_language}.template"
        } else {
            template_file = "${configuration.template_name}-nolog-${configuration.template_language}.template"
        }

        // String job_log_line_fix = job_log.replace("\n", "\n\n")
        String job_log_line_fix = job_log.replaceAll(/\h/, '&emsp;').replace("\\n", "  \n").replace("\\r", "  \n")
        template_args = [
            "job_log":job_log,
            "job_log_line_fix":job_log_line_fix,
            "job_status":job_status,
            "color":color,
            "log_snipped":log_snipped,
            "icon_base64":icon_base64 ]
        template_args.putAll(["execution":execution])
        this.sendTeamsNotification(execution, configuration, template_file, template_args)
    }

    onstart {
        type = "START"
        color = "696969"
        job_status = "NG"
        this.handleTrigger(execution, configuration, job_status, color)
        return true
    }

    onfailure {
        type = "FAILURE"
        color = "E81123"
        job_status = "NG"
        this.handleTrigger(execution, configuration, job_status, color)
        return true
    }

    onsuccess {
        type = "SUCCESS"
        color = "228B22"
        job_status = "OK"
        this.handleTrigger(execution, configuration, job_status, color)
        return true
    }
}
