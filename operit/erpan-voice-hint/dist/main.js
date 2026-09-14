"use strict";

var exports = {};
var HINT_RE = /<!--ERPAN_HINT-->([\s\S]*?)<!--\/ERPAN_HINT-->/g;
function escapeXml(value) {
    return String(value || "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&apos;");
}

function buildAttachmentTag(content) {
    var attachmentId = "erpan_voice_hint_" + Date.now();
    var escapedContent = escapeXml(content);
    var attributes = [
        'id="' + attachmentId + '"',
        'filename="耳畔声音线索.txt"',
        'type="text/plain"',
        'size="' + content.length + '"'
    ].join(" ");
    return '<attachment ' + attributes + '>' + escapedContent + '</attachment>';
}

function registerToolPkg() {
    ToolPkg.registerPromptInputHook({
        id: "erpan_voice_hint_prompt_input",
        function: onPromptInput
    });
    console.info("[erpan-voice-hint] plugin registered");
    return true;
}

function onPromptInput(input) {
    var stage = String(input.eventPayload.stage || input.eventName || "");
    if (stage !== "before_process") {
        return null;
    }
    var processedInput = String(input.eventPayload.processedInput || input.eventPayload.rawInput || "");
    if (!processedInput) {
        return null;
    }
    if (processedInput.indexOf("<!--ERPAN_HINT-->") === -1) {
        return null;
    }
    var modified = processedInput.replace(HINT_RE, function(match, content) {
        var trimmed = content.trim();
        if (!trimmed) return "";
        return buildAttachmentTag(trimmed);
    });
    modified = modified.replace(/\n\s*\n\s*\n/g, "\n\n").trim();
    if (modified === processedInput) {
        return null;
    }
    console.info("[erpan-voice-hint] hint converted, length=" + modified.length);
    return modified;
}

exports.registerToolPkg = registerToolPkg;
exports.onPromptInput = onPromptInput;
