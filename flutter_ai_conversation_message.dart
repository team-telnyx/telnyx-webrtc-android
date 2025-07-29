/// Model for AI conversation messages received from the WebSocket
class AiConversationMessage {
  String? id;
  String? jsonrpc;
  String? method;
  AiConversationParams? params;

  AiConversationMessage({this.id, this.jsonrpc, this.method, this.params});

  AiConversationMessage.fromJson(Map<String, dynamic> json) {
    id = json['id'];
    jsonrpc = json['jsonrpc'];
    method = json['method'];
    params = json['params'] != null
        ? AiConversationParams.fromJson(json['params'])
        : null;
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    data['id'] = id;
    data['jsonrpc'] = jsonrpc;
    data['method'] = method;
    if (params != null) {
      data['params'] = params!.toJson();
    }
    return data;
  }
}

/// Parameters for AI conversation messages
class AiConversationParams {
  String? type;
  WidgetSettings? widgetSettings;
  // Fields for response.created
  ResponseData? response;
  // Fields for response.text.delta
  int? contentIndex;
  String? delta;
  String? itemId;
  int? outputIndex;
  String? responseId;
  // Fields for conversation.item.created
  ConversationItem? item;
  String? previousItemId;

  AiConversationParams({
    this.type,
    this.widgetSettings,
    this.response,
    this.contentIndex,
    this.delta,
    this.itemId,
    this.outputIndex,
    this.responseId,
    this.item,
    this.previousItemId,
  });

  AiConversationParams.fromJson(Map<String, dynamic> json) {
    type = json['type'];
    widgetSettings = json['widget_settings'] != null
        ? WidgetSettings.fromJson(json['widget_settings'])
        : null;
    response = json['response'] != null
        ? ResponseData.fromJson(json['response'])
        : null;
    contentIndex = json['content_index'];
    delta = json['delta'];
    itemId = json['item_id'];
    outputIndex = json['output_index'];
    responseId = json['response_id'];
    item = json['item'] != null
        ? ConversationItem.fromJson(json['item'])
        : null;
    previousItemId = json['previous_item_id'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    data['type'] = type;
    if (widgetSettings != null) {
      data['widget_settings'] = widgetSettings!.toJson();
    }
    if (response != null) {
      data['response'] = response!.toJson();
    }
    if (contentIndex != null) data['content_index'] = contentIndex;
    if (delta != null) data['delta'] = delta;
    if (itemId != null) data['item_id'] = itemId;
    if (outputIndex != null) data['output_index'] = outputIndex;
    if (responseId != null) data['response_id'] = responseId;
    if (item != null) data['item'] = item!.toJson();
    if (previousItemId != null) data['previous_item_id'] = previousItemId;
    return data;
  }
}

/// Widget settings configuration
class WidgetSettings {
  String? agentThinkingText;
  AudioVisualizerConfig? audioVisualizerConfig;
  String? defaultState;
  String? giveFeedbackUrl;
  String? logoIconUrl;
  String? position;
  String? reportIssueUrl;
  String? speakToInterruptText;
  String? startCallText;
  String? theme;
  String? viewHistoryUrl;

  WidgetSettings({
    this.agentThinkingText,
    this.audioVisualizerConfig,
    this.defaultState,
    this.giveFeedbackUrl,
    this.logoIconUrl,
    this.position,
    this.reportIssueUrl,
    this.speakToInterruptText,
    this.startCallText,
    this.theme,
    this.viewHistoryUrl,
  });

  WidgetSettings.fromJson(Map<String, dynamic> json) {
    agentThinkingText = json['agent_thinking_text'];
    audioVisualizerConfig = json['audio_visualizer_config'] != null
        ? AudioVisualizerConfig.fromJson(json['audio_visualizer_config'])
        : null;
    defaultState = json['default_state'];
    giveFeedbackUrl = json['give_feedback_url'];
    logoIconUrl = json['logo_icon_url'];
    position = json['position'];
    reportIssueUrl = json['report_issue_url'];
    speakToInterruptText = json['speak_to_interrupt_text'];
    startCallText = json['start_call_text'];
    theme = json['theme'];
    viewHistoryUrl = json['view_history_url'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    data['agent_thinking_text'] = agentThinkingText;
    if (audioVisualizerConfig != null) {
      data['audio_visualizer_config'] = audioVisualizerConfig!.toJson();
    }
    data['default_state'] = defaultState;
    data['give_feedback_url'] = giveFeedbackUrl;
    data['logo_icon_url'] = logoIconUrl;
    data['position'] = position;
    data['report_issue_url'] = reportIssueUrl;
    data['speak_to_interrupt_text'] = speakToInterruptText;
    data['start_call_text'] = startCallText;
    data['theme'] = theme;
    data['view_history_url'] = viewHistoryUrl;
    return data;
  }
}

/// Audio visualizer configuration
class AudioVisualizerConfig {
  String? color;
  String? preset;

  AudioVisualizerConfig({this.color, this.preset});

  AudioVisualizerConfig.fromJson(Map<String, dynamic> json) {
    color = json['color'];
    preset = json['preset'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    data['color'] = color;
    data['preset'] = preset;
    return data;
  }
}

/// Response data for response.created messages
class ResponseData {
  String? id;
  List<dynamic>? output;
  String? status;

  ResponseData({this.id, this.output, this.status});

  ResponseData.fromJson(Map<String, dynamic> json) {
    id = json['id'];
    output = json['output'];
    status = json['status'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    data['id'] = id;
    data['output'] = output;
    data['status'] = status;
    return data;
  }
}

/// Conversation item for conversation.item.created messages
class ConversationItem {
  List<ConversationContent>? content;
  String? id;
  String? role;
  String? status;
  String? type;

  ConversationItem({this.content, this.id, this.role, this.status, this.type});

  ConversationItem.fromJson(Map<String, dynamic> json) {
    if (json['content'] != null) {
      content = <ConversationContent>[];
      json['content'].forEach((v) {
        content!.add(ConversationContent.fromJson(v));
      });
    }
    id = json['id'];
    role = json['role'];
    status = json['status'];
    type = json['type'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    if (content != null) {
      data['content'] = content!.map((v) => v.toJson()).toList();
    }
    data['id'] = id;
    data['role'] = role;
    data['status'] = status;
    data['type'] = type;
    return data;
  }
}

/// Content within a conversation item
class ConversationContent {
  String? transcript;
  String? type;
  String? text;

  ConversationContent({this.transcript, this.type, this.text});

  ConversationContent.fromJson(Map<String, dynamic> json) {
    transcript = json['transcript'];
    type = json['type'];
    text = json['text'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    if (transcript != null) data['transcript'] = transcript;
    if (type != null) data['type'] = type;
    if (text != null) data['text'] = text;
    return data;
  }
}