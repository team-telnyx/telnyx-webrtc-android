class AnonymousLoginMessage {
  String? id;
  String? jsonrpc;
  String? method;
  AnonymousLoginParams? params;

  AnonymousLoginMessage({this.id, this.jsonrpc, this.method, this.params});

  AnonymousLoginMessage.fromJson(Map<String, dynamic> json) {
    id = json['id'];
    jsonrpc = json['jsonrpc'];
    method = json['method'];
    params = json['params'] != null
        ? AnonymousLoginParams.fromJson(json['params'])
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

class AnonymousLoginParams {
  String? targetType;
  String? targetId;
  String? targetVersionId;
  Map<String, dynamic>? userVariables;
  bool? reconnection;
  UserAgent? userAgent;
  String? sessionId;

  AnonymousLoginParams({
    this.targetType,
    this.targetId,
    this.targetVersionId,
    this.userVariables,
    this.reconnection,
    this.userAgent,
    this.sessionId,
  });

  AnonymousLoginParams.fromJson(Map<String, dynamic> json) {
    targetType = json['target_type'];
    targetId = json['target_id'];
    targetVersionId = json['target_version_id'];
    userVariables = json['userVariables'];
    reconnection = json['reconnection'];
    sessionId = json['sessid'];
    userAgent = json['User-Agent'] != null
        ? UserAgent.fromJson(json['User-Agent'])
        : null;
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    data['target_type'] = targetType;
    data['target_id'] = targetId;
    if (targetVersionId != null) {
      data['target_version_id'] = targetVersionId;
    }
    if (userVariables != null) {
      data['userVariables'] = userVariables;
    }
    data['reconnection'] = reconnection;
    if (sessionId != null) {
      data['sessid'] = sessionId;
    }
    if (userAgent != null) {
      data['User-Agent'] = userAgent!.toJson();
    }
    return data;
  }
}

class UserAgent {
  String? sdkVersion;
  String? data;

  UserAgent({this.sdkVersion, this.data});

  UserAgent.fromJson(Map<String, dynamic> json) {
    sdkVersion = json['sdkVersion'];
    data = json['data'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    data['sdkVersion'] = sdkVersion;
    data['data'] = this.data;
    return data;
  }
}
