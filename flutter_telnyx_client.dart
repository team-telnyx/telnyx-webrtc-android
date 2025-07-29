import 'dart:async';
import 'dart:convert';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:telnyx_webrtc/config.dart';
import 'package:telnyx_webrtc/model/call_termination_reason.dart';
import 'package:telnyx_webrtc/model/network_reason.dart';
import 'package:telnyx_webrtc/model/verto/send/attach_call_message.dart';
import 'package:telnyx_webrtc/peer/peer.dart'
    if (dart.library.html) 'package:telnyx_webrtc/peer/web/peer.dart';
import 'package:telnyx_webrtc/call.dart';
import 'package:telnyx_webrtc/config/telnyx_config.dart';
import 'package:telnyx_webrtc/model/gateway_state.dart';
import 'package:telnyx_webrtc/model/socket_method.dart';
import 'package:telnyx_webrtc/model/telnyx_socket_error.dart';
import 'package:telnyx_webrtc/model/verto/receive/receive_bye_message_body.dart';
import 'package:telnyx_webrtc/model/verto/receive/received_message_body.dart';
import 'package:telnyx_webrtc/model/verto/receive/ai_conversation_message.dart';
import 'package:telnyx_webrtc/model/transcript_item.dart';
import 'package:telnyx_webrtc/model/verto/send/gateway_request_message_body.dart';
import 'package:telnyx_webrtc/model/verto/send/login_message_body.dart';
import 'package:telnyx_webrtc/model/verto/send/anonymous_login_message.dart';
import 'package:telnyx_webrtc/model/telnyx_message.dart';
import 'package:telnyx_webrtc/tx_socket.dart'
    if (dart.library.js) 'package:telnyx_webrtc/tx_socket_web.dart';
import 'package:telnyx_webrtc/utils/constants.dart';
import 'package:telnyx_webrtc/utils/logging/custom_logger.dart';
import 'package:telnyx_webrtc/utils/logging/default_logger.dart';
import 'package:telnyx_webrtc/utils/logging/global_logger.dart';
import 'package:telnyx_webrtc/utils/logging/log_level.dart';
import 'package:telnyx_webrtc/utils/preference_storage.dart';
import 'package:telnyx_webrtc/utils/version_utils.dart';
import 'package:telnyx_webrtc/utils/websocket_utils.dart';
import 'package:uuid/uuid.dart';
import 'package:flutter/foundation.dart';
import 'package:telnyx_webrtc/model/call_state.dart';
import 'package:telnyx_webrtc/model/jsonrpc.dart';
import 'package:telnyx_webrtc/model/push_notification.dart';
import 'package:telnyx_webrtc/model/verto/send/pong_message_body.dart';
import 'package:telnyx_webrtc/model/verto/send/ringing_ack_message.dart';
import 'package:telnyx_webrtc/model/verto/send/disable_push_body.dart';
import 'package:telnyx_webrtc/model/region.dart';

/// Callback for when the socket receives a message
typedef OnSocketMessageReceived = void Function(TelnyxMessage message);

/// Callback for when the socket receives an error
typedef OnSocketErrorReceived = void Function(TelnyxSocketError message);

/// Callback for when transcript updates occur
typedef OnTranscriptUpdate = void Function(List<TranscriptItem> transcript);

/// Represents the main entry point for interacting with the Telnyx RTC SDK.
///
/// This class manages the WebSocket connection to the Telnyx backend, handles
/// user authentication, and facilitates call creation and management. It provides
/// methods to connect, disconnect, send and receive calls, and monitor the
/// connection status.
///
/// Callbacks like [onSocketMessageReceived] and [onSocketErrorReceived] must be
/// implemented to handle events and errors from the socket.
class TelnyxClient {
  /// Callback for when the socket receives a message
  late OnSocketMessageReceived onSocketMessageReceived;

  /// Callback for when the socket receives an error
  late OnSocketErrorReceived onSocketErrorReceived;

  /// Callback for when transcript updates occur
  /// Note: this is only relevant for Assistant AI conversations
  OnTranscriptUpdate? onTranscriptUpdate;

  /// The path to the ringtone file (audio to play when receiving a call)
  String _ringtonePath = '';

  /// The path to the ringback file (audio to play when calling)
  String _ringBackpath = '';

  CustomLogger _logger = DefaultLogger();

  PushMetaData? _pushMetaData;
  bool _isAttaching = false;
  bool _debug = false;

  // Map to track reconnection timers for each call
  final Map<String?, Timer> _reconnectionTimers = {};

  // Current widget settings from AI conversation
  WidgetSettings? _currentWidgetSettings;

  // Transcript management
  final List<TranscriptItem> _transcript = [];
  final Map<String, StringBuffer> _assistantResponseBuffers = {};

  /// Default constructor for the TelnyxClient
  TelnyxClient() {
    onSocketMessageReceived = (TelnyxMessage message) {
      switch (message.socketMethod) {
        case SocketMethod.invite:
          {
            GlobalLogger().i(
              'TelnyxClient :: onSocketMessageReceived  Override this on client side: ${message.message}',
            );
            break;
          }
        case SocketMethod.bye:
          {
            GlobalLogger().i(
              'TelnyxClient :: onSocketMessageReceived  Override this on client side: ${message.message}',
            );
            break;
          }
        default:
          GlobalLogger().i(
            'TelnyxClient :: onSocketMessageReceived  Override this on client side: ${message.message}',
          );
      }
      GlobalLogger().i(
        'TelnyxClient :: onSocketMessageReceived  Override this on client side: ${message.message}',
      );
    };

    _checkReconnection();
  }

  /// The current instance of [TxSocket] associated with this client
  TxSocket txSocket = TxSocket(DefaultConfig.socketHostAddress);

  bool _closed = false;
  bool _connected = false;

  /// The current session ID related to this client
  String sessid = const Uuid().v4();

  Timer? _gatewayResponseTimer;
  bool _waitingForReg = true;
  bool _pendingAnswerFromPush = false;
  bool _pendingDeclineFromPush = false;
  bool _isCallFromPush = false;
  bool _registered = false;
  int _registrationRetryCounter = 0;

  /// Timer for handling push notification answer timeout
  Timer? _pendingAnswerTimeout;

  /// Timeout duration for pending answer from push (10 seconds)
  static const Duration _pushAnswerTimeoutDuration = Duration(seconds: 10);

  bool _autoReconnectLogin = true;
  int _connectRetryCounter = 0;

  /// The current gateway state for the socket connection
  String gatewayState = GatewayState.idle;

  /// A map of all current calls, with the call ID as the key and the [Call] object as the value.
  Map<String, Call> calls = {};

  /// The current active calls being handled by the TelnyxClient instance
  /// The Map key is the callId [String] and the value is the [Call] instance
  Map<String, Call> activeCalls() {
    return Map.fromEntries(
      calls.entries.where(
        (entry) =>
            entry.value.callState.isActive ||
            entry.value.callState.isDropped ||
            entry.value.callState.isReconnecting,
      ),
    );
  }

  /// Called when a call state changes to active
  /// This will cancel any reconnection timer for the call
  void onCallStateChangedToActive(String? callId) {
    if (callId != null) {
      GlobalLogger().i(
        'Call $callId state changed to ACTIVE, cancelling reconnection timer',
      );
      _cancelReconnectionTimer(callId);
    }
  }

  // For instances where the SDP is not contained within ANSWER, but received early via a MEDIA message
  bool _earlySDP = false;

  final String _storedHostAddress = DefaultConfig.socketHostAddress;

  /// Build the host address with region support
  String _buildHostAddress(Config config, {String? voiceSdkId}) {
    String baseHost = _storedHostAddress;

    // If region is not AUTO, prepend the region to the host
    if (config.region != Region.auto) {
      // Extract the base host from the WebSocket URL
      final uri = Uri.parse(_storedHostAddress);
      final hostWithoutProtocol = uri.host;
      final regionHost = '${config.region.value}.$hostWithoutProtocol';

      // Rebuild the WebSocket URL with the region
      baseHost = '${uri.scheme}://$regionHost:${uri.port}';

      GlobalLogger().i('Using region-specific host: $baseHost');
    }

    // Add voice SDK ID if provided
    if (voiceSdkId != null) {
      final uri = Uri.parse(baseHost);
      final newUri = uri.replace(queryParameters: {'voice_sdk_id': voiceSdkId});
      baseHost = newUri.toString();
    }

    return baseHost;
  }

  CredentialConfig? _storedCredentialConfig;

  TokenConfig? _storedTokenConfig;

  // Track current config for region fallback
  Config? _currentConfig;
  bool _isRegionFallbackAttempt = false;

  /// The stored [CredentialConfig] for the client - if no stored credential is present, this will be null
  CredentialConfig? get storedCredential => _storedCredentialConfig;

  /// The stored [TokenConfig] for the client - if no stored token is present, this will be null
  TokenConfig? get storedToken => _storedTokenConfig;

  /// The current widget settings from AI conversation
  WidgetSettings? get currentWidgetSettings => _currentWidgetSettings;

  /// Returns the forceRelayCandidate setting from the current config
  bool getForceRelayCandidate() {
    final config = _storedCredentialConfig ?? _storedTokenConfig;
    return config?.forceRelayCandidate ?? false;
  }

  /// Returns whether or not the client is connected to the socket connection
  bool isConnected() {
    return _connected;
  }

  /// Returns whether or not debug is enabled for the client
  bool isDebug() {
    return _debug;
  }

  /// Returns the current Gateway state for the socket connection
  String getGatewayStatus() {
    return gatewayState;
  }

  void _checkReconnection() {
    Connectivity().onConnectivityChanged.listen((
      List<ConnectivityResult> connectivityResult,
    ) {
      if (activeCalls().isEmpty || _isAttaching) return;

      if (connectivityResult.contains(ConnectivityResult.none)) {
        GlobalLogger().i('No available network types');
        _handleNetworkLost();
        return;
      }

      if (connectivityResult.contains(ConnectivityResult.mobile) ||
          connectivityResult.contains(ConnectivityResult.wifi)) {
        GlobalLogger().i('Network available: ${connectivityResult.join(", ")}');
        if (activeCalls().isNotEmpty && !_isAttaching) {
          _handleNetworkReconnection(NetworkReason.networkSwitch);
        }
      }
    });
  }

  /// Set the custom logger for the SDK
  void setCustomLogger(CustomLogger logger) {
    _logger = logger;
    GlobalLogger.logger = logger;
  }

  /// Set or adjust the log level for the SDK
  void setLogLevel(LogLevel level) {
    _logger.setLogLevel(level);
  }

  /// Get the custom logger for the SDK
  CustomLogger get logger => _logger;

  /// Gets the current conversation transcript
  List<TranscriptItem> get transcript => List.unmodifiable(_transcript);

  /// Clears the conversation transcript
  void clearTranscript() {
    _transcript.clear();
    _assistantResponseBuffers.clear();
    onTranscriptUpdate?.call(_transcript);
  }

  void _handleNetworkLost() {
    for (var call in activeCalls().values) {
      call.callHandler.onCallStateChanged.call(
        CallState.dropped.withNetworkReason(NetworkReason.networkLost),
      );
      // Start a reconnection timeout timer for this call
      _startReconnectionTimer(call);
    }
  }

  void _handleNetworkReconnection(NetworkReason reason) {
    _reconnectToSocket();
    for (var call in activeCalls().values) {
      if (call.callState.isDropped) {
        call.callHandler.onCallStateChanged.call(
          CallState.reconnecting.withNetworkReason(reason),
        );

        // Start a reconnection timeout timer for this call
        _startReconnectionTimer(call);
      }
    }
  }

  /// Starts a reconnection timer for a call
  /// If the call is still in RECONNECTING state after the timeout,
  /// it will be marked as DROPPED
  void _startReconnectionTimer(Call call) {
    // Cancel any existing timer for this call
    _cancelReconnectionTimer(call.callId);

    GlobalLogger().i('Starting reconnection timer for call ${call.callId}');

    // Create a new timer
    _reconnectionTimers[call.callId] = Timer(
      Duration(
        milliseconds: _storedCredentialConfig?.reconnectionTimeout ??
            _storedTokenConfig?.reconnectionTimeout ??
            Constants.reconnectionTimeout,
      ),
      () {
        // Check if the call is still in the reconnecting state
        if (calls.containsKey(call.callId)) {
          GlobalLogger().i('Reconnection timeout for call ${call.callId}');

          // Change the call state to dropped
          call.callHandler.onCallStateChanged.call(
            CallState.dropped.withNetworkReason(NetworkReason.networkLost),
          );

          // End the call
          call.endCall();
        }
        // Remove the timer from the map
        _reconnectionTimers.remove(call.callId);
      },
    );
  }

  /// Cancels the reconnection timer for a call
  void _cancelReconnectionTimer(String? callId) {
    if (callId != null && _reconnectionTimers.containsKey(callId)) {
      _reconnectionTimers[callId]?.cancel();
      _reconnectionTimers.remove(callId);
      GlobalLogger().i('Cancelled reconnection timer for call $callId');
    }
  }

  /// Starts the timeout timer for pending answer from push notification
  void _startPendingAnswerTimeout() {
    // Cancel any existing timeout
    _cancelPendingAnswerTimeout();

    GlobalLogger().i(
      'Starting pending answer timeout (${_pushAnswerTimeoutDuration.inSeconds}s)',
    );

    _pendingAnswerTimeout = Timer(_pushAnswerTimeoutDuration, () {
      _handlePendingAnswerTimeout();
    });
  }

  /// Cancels the pending answer timeout timer
  void _cancelPendingAnswerTimeout() {
    if (_pendingAnswerTimeout != null) {
      _pendingAnswerTimeout?.cancel();
      _pendingAnswerTimeout = null;
      GlobalLogger().i('Cancelled pending answer timeout');
    }
  }

  /// Handles the timeout when no INVITE is received after accepting from push
  void _handlePendingAnswerTimeout() {
    GlobalLogger().i(
      'Pending answer timeout expired - no INVITE received within ${_pushAnswerTimeoutDuration.inSeconds} seconds',
    );

    // Reset the pending answer flag
    _pendingAnswerFromPush = false;

    // Create termination reason for originator cancel
    final terminationReason = CallTerminationReason(
      cause: 'ORIGINATOR_CANCEL',
      causeCode: 487,
      sipCode: 487,
      sipReason: 'Request Terminated',
    );

    // Use call ID from push metadata, or generate a timeout-specific ID
    final callId = _pushMetaData?.callId ?? 'timeout-${const Uuid().v4()}';

    final byeMessage = ReceiveByeMessage(
      jsonrpc: JsonRPCConstant.jsonrpc,
      id: 0,
      method: SocketMethod.bye,
      params: ReceiveByeParams(
        callID: callId,
        sipCallId: callId,
        sipCode: terminationReason.sipCode,
        causeCode: terminationReason.causeCode,
        cause: terminationReason.cause,
        sipReason: terminationReason.sipReason,
      ),
    );

    final byeMessageJson = jsonEncode(byeMessage.toJson());
    GlobalLogger().i(
      'Sending BYE message due to pending answer timeout: $byeMessageJson',
    );

    final receivedMessage = ReceivedMessage(
      jsonrpc: JsonRPCConstant.jsonrpc,
      id: byeMessage.id,
      method: byeMessage.method,
      byeParams: byeMessage.params,
    );

    // Send the BYE message through the normal message flow
    // This will trigger the existing BYE handling in the ViewModel which:
    // 1. Calls resetCallInfo() to reset call state to idle
    // 2. Dismisses any loading dialogs (CircularProgressIndicator)
    // 3. Updates the UI to show the proper termination reason
    final message = TelnyxMessage(
      socketMethod: SocketMethod.bye,
      message: receivedMessage,
    );
    onSocketMessageReceived.call(message);

    // Clear the timeout timer reference
    _pendingAnswerTimeout = null;

    GlobalLogger().i(
      'Pending answer timeout handled - call terminated with ORIGINATOR_CANCEL',
    );
  }

  /// Handles an incoming push notification to initiate a call flow.
  ///
  /// This method connects the client and logs in using the provided configuration,
  /// preparing it to receive an incoming call invitation. It should be called when
  /// your application receives a push notification from Telnyx.
  ///
  /// **Note:** Do not call [connectWithCredential] or [connectWithToken] separately
  /// if you are using this method, as it handles the connection process internally.
  ///
  /// - [pushMetaData]: The metadata received from the push notification.
  /// - [credentialConfig]: The credential configuration for login (if using credentials).
  /// - [tokenConfig]: The token configuration for login (if using a token).
  void handlePushNotification(
    PushMetaData pushMetaData,
    CredentialConfig? credentialConfig,
    TokenConfig? tokenConfig,
  ) {
    GlobalLogger().i(
      'TelnyxClient.handlePushNotification: Called. PushMetaData: ${jsonEncode(pushMetaData.toJson())}',
    );

    if (pushMetaData.isDecline == true) {
      GlobalLogger().i(
        'TelnyxClient.handlePushNotification: Decline case - using simplified decline logic with decline_push parameter',
      );
      // For decline, we use a simplified approach: connect, login with decline_push=true, then disconnect
      _connectWithCallBack(pushMetaData, () {
        if (credentialConfig != null) {
          _credentialLoginWithDecline(credentialConfig);
        } else if (tokenConfig != null) {
          _tokenLoginWithDecline(tokenConfig);
        }
      });
      return;
    }

    // For accept and normal cases, use the existing logic
    _isCallFromPush = true;
    if (pushMetaData.isAnswer == true) {
      GlobalLogger().i(
        'TelnyxClient.handlePushNotification: _pendingAnswerFromPush will be set to true',
      );
      _pendingAnswerFromPush = true;
      // Start the timeout timer for pending answer
      _startPendingAnswerTimeout();
    } else {
      GlobalLogger().i(
        'TelnyxClient.handlePushNotification: _pendingAnswerFromPush remains false',
      );
    }

    _connectWithCallBack(pushMetaData, () {
      if (credentialConfig != null) {
        credentialLogin(credentialConfig);
      } else if (tokenConfig != null) {
        tokenLogin(tokenConfig);
      }
    });
  }

  /// Internal method for credential login with decline_push parameter
  void _credentialLoginWithDecline(CredentialConfig config) {
    GlobalLogger().i(
      'TelnyxClient._credentialLoginWithDecline: Sending login with decline_push=true',
    );
    final uuid = const Uuid().v4();
    final user = config.sipUser;
    final password = config.sipPassword;
    final notificationToken = config.notificationToken;
    UserVariables? notificationParams;

    notificationParams = UserVariables(
      pushDeviceToken: notificationToken,
      pushNotificationProvider:
          defaultTargetPlatform == TargetPlatform.android ? 'android' : 'ios',
    );

    final loginParams = LoginParams(
      login: user,
      passwd: password,
      loginParams: {'decline_push': 'true'},
      sessionId: sessid,
      userVariables: notificationParams,
    );
    final loginMessage = LoginMessage(
      id: uuid,
      method: SocketMethod.login,
      params: loginParams,
      jsonrpc: JsonRPCConstant.jsonrpc,
    );

    final String jsonLoginMessage = jsonEncode(loginMessage);
    txSocket.send(jsonLoginMessage);

    // Disconnect after sending the decline login message
    Timer(const Duration(milliseconds: 1000), () {
      GlobalLogger().i(
        'TelnyxClient._credentialLoginWithDecline: Disconnecting after decline login',
      );
      disconnect();
    });
  }

  /// Internal method for token login with decline_push parameter
  void _tokenLoginWithDecline(TokenConfig config) {
    GlobalLogger().i(
      'TelnyxClient._tokenLoginWithDecline: Sending login with decline_push=true',
    );
    final uuid = const Uuid().v4();
    final token = config.sipToken;
    final notificationToken = config.notificationToken;
    UserVariables? notificationParams;

    notificationParams = UserVariables(
      pushDeviceToken: notificationToken,
      pushNotificationProvider:
          defaultTargetPlatform == TargetPlatform.android ? 'android' : 'ios',
    );

    final loginParams = LoginParams(
      loginToken: token,
      loginParams: {'decline_push': 'true'},
      userVariables: notificationParams,
      sessionId: sessid,
    );
    final loginMessage = LoginMessage(
      id: uuid,
      method: SocketMethod.login,
      params: loginParams,
      jsonrpc: JsonRPCConstant.jsonrpc,
    );

    final String jsonLoginMessage = jsonEncode(loginMessage);
    txSocket.send(jsonLoginMessage);

    // Disconnect after sending the decline login message
    Timer(const Duration(milliseconds: 1000), () {
      GlobalLogger().i(
        'TelnyxClient._tokenLoginWithDecline: Disconnecting after decline login',
      );
      disconnect();
    });
  }

  /// Sets the push metadata for the client and saves it to the shared preferences
  /// The [isAnswer] flag is used to determine if the push notification indicates that we should answer the pending invite
  /// The [isDecline] flag is used to determine if the push notification indicates that we should decline the pending invite
  static void setPushMetaData(
    Map<String, dynamic> pushMetaData, {
    bool isAnswer = false,
    bool isDecline = false,
  }) {
    final Map<String, dynamic> metaData = jsonDecode(pushMetaData['metadata']);
    metaData['isAnswer'] = isAnswer;
    metaData['isDecline'] = isDecline;
    PreferencesStorage.saveMetadata(jsonEncode(metaData));
  }

  /// Gets the push metadata for the client
  static Future<Map<String, dynamic>?> getPushData() async {
    return await PreferencesStorage.getMetaData();
  }

  /// Clears the push metadata for the client
  static void clearPushMetaData() {
    PreferencesStorage.saveMetadata('');
  }

  /// Create a socket connection for
  /// communication with the Telnyx backend
  void _connectWithCallBack(
    PushMetaData? pushMetaData,
    OnOpenCallback openCallback,
  ) {
    GlobalLogger().i(
      'TelnyxClient._connectWithCallBack: Called. PushMetaData: ${pushMetaData?.toJson()}',
    );
    if (pushMetaData != null) {
      _pushMetaData = pushMetaData;
    }
    try {
      if (pushMetaData?.voiceSdkId != null) {
        txSocket.hostAddress =
            '$_storedHostAddress?voice_sdk_id=${pushMetaData?.voiceSdkId}';
        GlobalLogger().i(
          'Connecting to WebSocket with voice_sdk_id :: ${pushMetaData?.voiceSdkId}',
        );
      } else {
        txSocket.hostAddress = _storedHostAddress;
        GlobalLogger().i(
          'TelnyxClient._connectWithCallBack: connecting to WebSocket $_storedHostAddress',
        );
      }
      txSocket
        ..connect()
        ..onOpen = () {
          _closed = false;
          _connected = true;
          GlobalLogger().i(
            'TelnyxClient._connectWithCallBack (via _onOpen): Web Socket is now connected',
          );
          _onOpen();
          openCallback.call();
        }
        ..onMessage = (dynamic data) {
          _onMessage(data);
        }
        ..onClose = (int closeCode, String closeReason) {
          GlobalLogger().i('Closed [$closeCode, $closeReason]!');
          _connected = false;
          final wasClean = WebSocketUtils.isCleanClose(closeCode, closeReason);
          _onClose(wasClean, closeCode, closeReason);
        };
    } catch (e, string) {
      GlobalLogger().e('${e.toString()} :: $string');
      _connected = false;
      GlobalLogger().e('WebSocket $_storedHostAddress error: $e');
    }
  }

  /// Connects to the WebSocket using the provided [tokenConfig]
  void connectWithToken(TokenConfig tokenConfig) {
    // Store current config for potential fallback
    _currentConfig = tokenConfig;

    // First check if there is a custom logger set within the config - if so, we set it here
    _logger = tokenConfig.customLogger ?? DefaultLogger();
    GlobalLogger.logger = _logger;
    GlobalLogger().i('TelnyxClient.connectWithToken: Attempting to connect.');

    // Now that a logger is set, we can set the log level
    _logger
      ..setLogLevel(tokenConfig.logLevel)
      ..log(LogLevel.info, 'connect()')
      ..log(LogLevel.info, 'connecting to WebSocket $_storedHostAddress');
    try {
      // Build the host address with region support
      final hostAddress = _buildHostAddress(
        tokenConfig,
        voiceSdkId: _pushMetaData?.voiceSdkId,
      );

      txSocket.hostAddress = hostAddress;
      GlobalLogger().i('connecting to WebSocket $hostAddress');
      txSocket
        ..onOpen = () {
          _closed = false;
          _connected = true;
          _isRegionFallbackAttempt =
              false; // Reset fallback flag on successful connection
          GlobalLogger().i(
            'TelnyxClient.connectWithToken (via _onOpen): Web Socket is now connected',
          );
          _onOpen();
          tokenLogin(tokenConfig);
        }
        ..onMessage = (dynamic data) {
          _onMessage(data);
        }
        ..onClose = (int closeCode, String closeReason) {
          GlobalLogger().i('Closed [$closeCode, $closeReason]!');
          _connected = false;
          final wasClean = WebSocketUtils.isCleanClose(closeCode, closeReason);
          _onClose(wasClean, closeCode, closeReason);
        }
        ..connect();
    } catch (e) {
      GlobalLogger().e(e.toString());
      _connected = false;
      GlobalLogger().e('WebSocket $_storedHostAddress error: $e');
    }
  }

  /// Connects to the WebSocket using the provided [CredentialConfig]
  void connectWithCredential(CredentialConfig credentialConfig) {
    // Store current config for potential fallback
    _currentConfig = credentialConfig;

    // First check if there is a custom logger set within the config - if so, we set it here
    // Use custom logger if provided or fallback to default.
    _logger = credentialConfig.customLogger ?? DefaultLogger();
    GlobalLogger.logger = _logger;
    GlobalLogger().i(
      'TelnyxClient.connectWithCredential: Attempting to connect.',
    );

    // Now that a logger is set, we can set the log level
    _logger
      ..setLogLevel(credentialConfig.logLevel)
      ..log(LogLevel.info, 'connect()');
    try {
      // Build the host address with region support
      final hostAddress = _buildHostAddress(
        credentialConfig,
        voiceSdkId: _pushMetaData?.voiceSdkId,
      );

      txSocket.hostAddress = hostAddress;
      GlobalLogger().i('connecting to WebSocket $hostAddress');
      txSocket
        ..onOpen = () {
          _closed = false;
          _connected = true;
          _isRegionFallbackAttempt =
              false; // Reset fallback flag on successful connection
          GlobalLogger().i(
            'TelnyxClient.connectWithCredential (via _onOpen): Web Socket is now connected',
          );
          _onOpen();
          credentialLogin(credentialConfig);
        }
        ..onMessage = (dynamic data) {
          _onMessage(data);
        }
        ..onClose = (int closeCode, String closeReason) {
          GlobalLogger().i('Closed [$closeCode, $closeReason]!');
          _connected = false;
          bool wasClean = WebSocketUtils.isCleanClose(closeCode, closeReason);
          _onClose(wasClean, closeCode, closeReason);
        }
        ..connect();
    } catch (e) {
      GlobalLogger().e(e.toString());
      _connected = false;
      GlobalLogger().e('WebSocket $_storedHostAddress error: $e');
    }
  }

  @Deprecated(
    'Use connect with token or credential login i.e connectWithCredential(..) or connectWithToken(..)',
  )

  /// Connects to the WebSocket with a previously provided [Config]
  void connect() {
    GlobalLogger().i('connect()');
    if (isConnected()) {
      GlobalLogger().i('WebSocket $_storedHostAddress is already connected');
      return;
    }
    GlobalLogger().i('connecting to WebSocket $_storedHostAddress');
    try {
      if (_pushMetaData != null) {
        txSocket.hostAddress =
            '$_storedHostAddress?voice_sdk_id=${_pushMetaData?.voiceSdkId}';
        GlobalLogger().i(
          'Connecting to WebSocket with voice_sdk_id :: ${_pushMetaData?.voiceSdkId}',
        );
      } else {
        txSocket.hostAddress = _storedHostAddress;
        GlobalLogger().i('connecting to WebSocket $_storedHostAddress');
      }
      txSocket
        ..onOpen = () {
          _closed = false;
          _connected = true;
          GlobalLogger().i('Web Socket is now connected');
          _onOpen();
        }
        ..onMessage = (dynamic data) {
          _onMessage(data);
        }
        ..onClose = (int closeCode, String closeReason) {
          GlobalLogger().i('Closed [$closeCode, $closeReason]!');
          _connected = false;
          bool wasClean = WebSocketUtils.isCleanClose(closeCode, closeReason);
          _onClose(wasClean, closeCode, closeReason);
        }
        ..connect();
    } catch (e) {
      GlobalLogger().e(e.toString());
      _connected = false;
      GlobalLogger().e('WebSocket $_storedHostAddress error: $e');
    }
  }

  void _reconnectToSocket() {
    _isAttaching = true;
    Timer(Duration(milliseconds: Constants.gatewayResponseDelay), () {
      _isAttaching = false;
    });

    txSocket.close();
    // Delay to allow connection
    Timer(const Duration(seconds: 1), () {
      if (_storedCredentialConfig != null) {
        connectWithCredential(_storedCredentialConfig!);
      } else if (_storedTokenConfig != null) {
        connectWithToken(_storedTokenConfig!);
      }
    });
  }

  /// The current instance of [Call] associated with this client. Can be used
  /// to call call related functions such as hold/mute
  Call? _call;

  // Public getter to lazily initialize and return the value.
  @Deprecated(
    'telnyxClient.call is deprecated, use telnyxClient.invite() or  telnyxClient.accept()',
  )

  /// The current instance of [Call] associated with this client.
  ///
  /// This is deprecated. Use [newInvite] to create a new call or
  /// [acceptCall] to answer an incoming one. For existing calls, retrieve them
  /// from the [calls] map using their call ID.
  Call get call {
    // If _call is null, initialize it with the default value.
    _call ??= _createCall();
    return _call!;
  }

  void _callEnded() {
    GlobalLogger().i('Call Ended');
    _call = null;
  }

  /// Creates an instance of [Call] that can be used to create invitations or
  /// perform common call related functions such as ending the call or placing
  /// yourself on hold/mute.
  Call _createCall() {
    // Create a placeholder for the CallHandler
    late CallHandler callHandler;

    // Create the Call object
    _call = Call(
      txSocket,
      this,
      sessid,
      _ringtonePath,
      _ringBackpath,
      callHandler = CallHandler((state) {
        GlobalLogger().i(
          'Call state not overridden :Call State Changed to $state',
        );
      }, null),
      // Pass null initially
      _callEnded,
      _debug,
    );

    // Set the call property of CallHandler
    callHandler.call = _call!;

    return _call!;
  }

  /// Uses the provided [config] to send a credential login message to the Telnyx backend.
  /// If successful, the gateway registration process will start.
  ///
  /// May return a [TelnyxSocketError] in the case of an authentication error
  @Deprecated('Use connectWithCredential(..) instead')
  void credentialLogin(CredentialConfig config) {
    _storedCredentialConfig = config;
    final uuid = const Uuid().v4();
    final user = config.sipUser;
    final password = config.sipPassword;
    final fcmToken = config.notificationToken;
    _ringBackpath = config.ringbackPath ?? '';
    _ringtonePath = config.ringTonePath ?? '';
    _debug = config.debug;
    UserVariables? notificationParams;
    _autoReconnectLogin = config.autoReconnect ?? true;

    if (defaultTargetPlatform == TargetPlatform.android) {
      notificationParams = UserVariables(
        pushDeviceToken: fcmToken,
        pushNotificationProvider: 'android',
      );
    } else if (defaultTargetPlatform == TargetPlatform.iOS) {
      notificationParams = UserVariables(
        pushDeviceToken: fcmToken,
        pushNotificationProvider: 'ios',
      );
    }

    final loginParams = LoginParams(
      login: user,
      passwd: password,
      loginParams: {'attach_call': 'true'},
      sessionId: sessid,
      userVariables: notificationParams,
    );
    final loginMessage = LoginMessage(
      id: uuid,
      method: SocketMethod.login,
      params: loginParams,
      jsonrpc: JsonRPCConstant.jsonrpc,
    );

    final String jsonLoginMessage = jsonEncode(loginMessage);
    if (isConnected()) {
      txSocket.send(jsonLoginMessage);
    } else {
      _connectWithCallBack(_pushMetaData, () {
        txSocket.send(jsonLoginMessage);
      });
    }
  }

  /// Uses the provided [config] to send a token login message to the Telnyx backend.
  /// If successful, the gateway registration process will start.
  ///
  /// May return a [TelnyxSocketError] in the case of an authentication error
  @Deprecated('Use connectWithToken(..) instead')
  void tokenLogin(TokenConfig config) {
    _storedTokenConfig = config;
    final uuid = const Uuid().v4();
    final token = config.sipToken;
    final fcmToken = config.notificationToken;
    _ringBackpath = config.ringbackPath ?? '';
    _ringtonePath = config.ringTonePath ?? '';
    _debug = config.debug;
    UserVariables? notificationParams;
    _autoReconnectLogin = config.autoReconnect ?? true;

    if (defaultTargetPlatform == TargetPlatform.android) {
      notificationParams = UserVariables(
        pushDeviceToken: fcmToken,
        pushNotificationProvider: 'android',
      );
    } else if (defaultTargetPlatform == TargetPlatform.iOS) {
      notificationParams = UserVariables(
        pushDeviceToken: fcmToken,
        pushNotificationProvider: 'ios',
      );
    }

    final loginParams = LoginParams(
      loginToken: token,
      loginParams: {'attach_call': 'true'},
      userVariables: notificationParams,
      sessionId: sessid,
    );
    final loginMessage = LoginMessage(
      id: uuid,
      method: SocketMethod.login,
      params: loginParams,
      jsonrpc: JsonRPCConstant.jsonrpc,
    );

    final String jsonLoginMessage = jsonEncode(loginMessage);
    GlobalLogger().i('Token Login Message $jsonLoginMessage');
    if (isConnected()) {
      txSocket.send(jsonLoginMessage);
    } else {
      _connectWithCallBack(null, () {
        txSocket.send(jsonLoginMessage);
      });
    }
  }

  /// Performs an anonymous login to the Telnyx backend for AI assistant connections.
  ///
  /// This method allows connecting to AI assistants without traditional authentication.
  /// It takes the target ID, target type (defaults to 'ai_assistant'), and optional
  /// target version ID.
  ///
  /// Note: Any call, no matter the destination, after this login will be directed to the AI agent specified by the target ID.
  ///
  /// Parameters:
  /// - [targetId]: The ID of the target (e.g., assistant ID)
  /// - [targetType]: The type of target (defaults to 'ai_assistant')
  /// - [targetVersionId]: Optional version ID of the target
  /// - [userVariables]: Optional user variables to include
  /// - [reconnection]: Whether this is a reconnection attempt (defaults to false)
  Future<void> anonymousLogin({
    required String targetId,
    String targetType = 'ai_assistant',
    String? targetVersionId,
    Map<String, dynamic>? userVariables,
    bool reconnection = false,
  }) async {
    final uuid = const Uuid().v4();

    final versionData = await VersionUtils.getSDKVersion();
    final userAgentData = await VersionUtils.getUserAgent();

    final userAgent = UserAgent(
      sdkVersion: versionData,
      data: userAgentData,
    );

    final anonymousLoginParams = AnonymousLoginParams(
      targetType: targetType,
      targetId: targetId,
      targetVersionId: targetVersionId,
      userVariables: userVariables,
      reconnection: reconnection,
      userAgent: userAgent,
      sessionId: sessid,
    );

    final anonymousLoginMessage = AnonymousLoginMessage(
      id: uuid,
      method: SocketMethod.anonymousLogin,
      params: anonymousLoginParams,
      jsonrpc: JsonRPCConstant.jsonrpc,
    );

    final String jsonAnonymousLoginMessage = jsonEncode(anonymousLoginMessage);
    GlobalLogger().i('Anonymous Login Message $jsonAnonymousLoginMessage');

    if (isConnected()) {
      txSocket.send(jsonAnonymousLoginMessage);
    } else {
      _connectWithCallBack(null, () {
        txSocket.send(jsonAnonymousLoginMessage);
      });
    }
  }

  /// Disables push notifications for the currently authenticated user.
  ///
  /// This method requires a user to be logged in via [connectWithCredential] or
  /// [connectWithToken] and have a `notificationToken` provided in the config.
  /// It sends a request to the Telnyx backend to stop sending push notifications
  /// to the associated device token.
  void disablePushNotifications() {
    final config = _storedCredentialConfig ?? _storedTokenConfig;
    if (config != null && config.notificationToken != null) {
      final uuid = const Uuid().v4();
      final disablePushParams = DisablePushParams(
        user: config is CredentialConfig ? config.sipUser : null,
        loginToken: config is TokenConfig ? config.sipToken : null,
        userVariables: PushUserVariables(
          pushNotificationToken: config.notificationToken!,
          pushNotificationProvider:
              defaultTargetPlatform == TargetPlatform.android
                  ? 'android'
                  : 'ios',
        ),
      );
      final disablePushMessage = DisablePushMessage(
        id: uuid,
        method: SocketMethod.disablePush,
        params: disablePushParams,
        jsonrpc: JsonRPCConstant.jsonrpc,
      );

      final String jsonDisablePushMessage = jsonEncode(disablePushMessage);
      txSocket.send(jsonDisablePushMessage);
    } else {
      GlobalLogger().e(
        'No user or associated notification token found - we cannot disable push notifications',
      );
    }
  }

  /// Creates and sends a new call invitation.
  ///
  /// This method initiates an outgoing call to a [destinationNumber] (which can be
  /// a SIP URI or a phone number).
  ///
  /// - [callerName]: The name to be displayed to the callee.
  /// - [callerNumber]: The phone number or SIP URI of the caller.
  /// - [destinationNumber]: The phone number or SIP URI to call.
  /// - [clientState]: A custom string that can be used to store and retrieve state
  ///   between applications. It is passed to the remote party.
  /// - [customHeaders]: Optional custom SIP headers to add to the INVITE message.
  /// - [debug]: Enables detailed logging for this specific call if set to true.
  ///
  /// Returns a [Call] object representing the new outgoing call.
  Call newInvite(
    String callerName,
    String callerNumber,
    String destinationNumber,
    String clientState, {
    Map<String, String> customHeaders = const {},
    bool debug = false,
  }) {
    final Call inviteCall = _createCall()
      ..sessionCallerName = callerName
      ..sessionCallerNumber = callerNumber
      ..sessionDestinationNumber = destinationNumber
      ..sessionClientState = clientState;
    customHeaders = customHeaders;
    inviteCall.callId = const Uuid().v4();
    final base64State = base64.encode(utf8.encode(clientState));
    updateCall(inviteCall);

    // Create the peer connection with debug enabled if requested
    inviteCall.peerConnection = Peer(
      inviteCall.txSocket,
      debug || _debug,
      this,
      getForceRelayCandidate(),
    );
    inviteCall.peerConnection?.invite(
      callerName,
      callerNumber,
      destinationNumber,
      base64State,
      inviteCall.callId!,
      inviteCall.sessid,
      customHeaders,
    );

    if (debug) {
      inviteCall.initCallMetrics();
    } //play ringback tone
    inviteCall.playAudio(_ringBackpath);
    inviteCall.callHandler.changeState(CallState.newCall);
    return inviteCall;
  }

  /// Accepts an incoming call.
  ///
  /// This method should be called in response to an `invite` event received via
  /// the [onSocketMessageReceived] callback.
  ///
  /// - [invite]: The [IncomingInviteParams] object from the received invite message.
  /// - [callerName]: The name of the user accepting the call.
  /// - [callerNumber]: The number or SIP URI of the user accepting the call.
  /// - [clientState]: A custom string for application-specific state.
  /// - [isAttach]: Set to true if this is a call being re-attached (e.g., after network reconnection).
  /// - [customHeaders]: Optional custom SIP headers to add to the response.
  /// - [debug]: Enables detailed logging for this specific call if set to true.
  ///
  /// Returns the [Call] object associated with the accepted call.
  Call acceptCall(
    IncomingInviteParams invite,
    String callerName,
    String callerNumber,
    String clientState, {
    bool isAttach = false,
    Map<String, String> customHeaders = const {},
    bool debug = false,
  }) {
    final Call answerCall = getCallOrNull(invite.callID!) ?? _createCall()
      ..callId = invite.callID
      ..sessionCallerName = callerName
      ..sessionCallerNumber = callerNumber
      ..callState = CallState.connecting
      ..sessionDestinationNumber = invite.callerIdNumber ?? '-1'
      ..sessionClientState = clientState;

    final destinationNum = invite.callerIdNumber;

    // Create the peer connection
    answerCall.peerConnection = Peer(
      txSocket,
      debug || _debug,
      this,
      getForceRelayCandidate(),
    );

    // Set up the session with the callback if debug is enabled
    answerCall.peerConnection?.accept(
      callerName,
      callerNumber,
      destinationNum!,
      clientState,
      answerCall.callId!,
      invite,
      customHeaders,
      isAttach,
    );
    answerCall.callHandler.changeState(CallState.connecting);
    if (debug) {
      answerCall.initCallMetrics();
    }
    answerCall.stopAudio();
    if (answerCall.callId != null) {
      updateCall(answerCall);
    }
    clearPushMetaData();
    return answerCall;
  }

  /// Provides the current [Call] instance associated with the [callId] otherwise returns null
  Call? getCallOrNull(String callId) {
    if (calls.containsKey(callId)) {
      GlobalLogger().d('Invite Call found');
      return calls[callId];
    }
    GlobalLogger().d('Invite Call not found');
    return null;
  }

  /// Update the [Call] instance associated with the [callId]
  void updateCall(Call call) {
    if (calls.containsKey(call.callId)) {
      calls[call.callId!] = call;
    } else {
      calls[call.callId!] = call;
    }
  }

  /// Closes the socket connection and provides a callback upon completion.
  ///
  /// This method logs the user out and terminates the WebSocket connection.
  /// The [closeCallback] is invoked when the disconnection is complete.
  void disconnectWithCallBack(OnCloseCallback? closeCallback) {
    _invalidateGatewayResponseTimer();
    _resetGatewayCounters();
    // Cancel any pending answer timeout
    _cancelPendingAnswerTimeout();
    clearPushMetaData();
    GlobalLogger().i('disconnect()');
    if (_closed) {
      GlobalLogger().i('WebSocket is already closed');
      closeCallback?.call(0, 'Client send disconnect');
      return;
    }
    // Don't wait for the WebSocket 'close' event, do it now.
    _closed = true;
    _connected = false;
    _registered = false;
    try {
      txSocket.close();
      Future.delayed(const Duration(milliseconds: 100), () {
        closeCallback?.call(0, 'Client send disconnect');
      });
    } catch (error) {
      GlobalLogger().e('close() | error closing the WebSocket: $error');
    }
  }

  /// Closes the socket connection, effectively logging the user out.
  void disconnect() {
    _invalidateGatewayResponseTimer();
    _resetGatewayCounters();
    // Cancel any pending answer timeout
    _cancelPendingAnswerTimeout();
    clearPushMetaData();
    GlobalLogger().i('disconnect()');
    if (_closed) return;
    // Don't wait for the WebSocket 'close' event, do it now.
    _closed = true;
    _connected = false;
    _registered = false;
    _onClose(true, 0, 'Client send disconnect');
    try {
      txSocket.close();
    } catch (error) {
      GlobalLogger().e('close() | error closing the WebSocket: $error');
    }
  }

  /// WebSocket Event Handlers
  void _onOpen() {
    GlobalLogger().i(
      'TelnyxClient._onOpen: WebSocket connected event triggered.',
    );
  }

  void _onClose(bool wasClean, int code, String reason) {
    GlobalLogger().i('WebSocket closed');
    if (wasClean == false) {
      GlobalLogger().i('WebSocket abrupt disconnection');
    }

    // Handle region fallback if connection failed and fallback is enabled
    if (!wasClean &&
        _currentConfig != null &&
        _currentConfig!.region != Region.auto &&
        _currentConfig!.fallbackOnRegionFailure &&
        !_isRegionFallbackAttempt) {
      GlobalLogger().i(
        'Connection failed with region ${_currentConfig!.region.value}, attempting fallback to auto region',
      );
      _isRegionFallbackAttempt = true;

      // Create a fallback config with auto region
      Config fallbackConfig;
      if (_currentConfig is TokenConfig) {
        final tokenConfig = _currentConfig as TokenConfig;
        fallbackConfig = TokenConfig(
          sipToken: tokenConfig.sipToken,
          sipCallerIDName: tokenConfig.sipCallerIDName,
          sipCallerIDNumber: tokenConfig.sipCallerIDNumber,
          notificationToken: tokenConfig.notificationToken,
          region: Region.auto,
          // Force auto region for fallback
          fallbackOnRegionFailure: tokenConfig.fallbackOnRegionFailure,
          logLevel: tokenConfig.logLevel,
          customLogger: tokenConfig.customLogger,
          reconnectionTimeout: tokenConfig.reconnectionTimeout,
          debug: tokenConfig.debug,
        );

        // Retry connection with auto region
        Timer(const Duration(milliseconds: 1000), () {
          connectWithToken(fallbackConfig as TokenConfig);
        });
      } else if (_currentConfig is CredentialConfig) {
        final credConfig = _currentConfig as CredentialConfig;
        fallbackConfig = CredentialConfig(
          sipUser: credConfig.sipUser,
          sipPassword: credConfig.sipPassword,
          sipCallerIDName: credConfig.sipCallerIDName,
          sipCallerIDNumber: credConfig.sipCallerIDNumber,
          notificationToken: credConfig.notificationToken,
          region: Region.auto,
          // Force auto region for fallback
          fallbackOnRegionFailure: credConfig.fallbackOnRegionFailure,
          logLevel: credConfig.logLevel,
          customLogger: credConfig.customLogger,
          reconnectionTimeout: credConfig.reconnectionTimeout,
          debug: credConfig.debug,
        );

        // Retry connection with auto region
        Timer(const Duration(milliseconds: 1000), () {
          connectWithCredential(fallbackConfig as CredentialConfig);
        });
      }
    }
  }

  void _onMessage(dynamic data) async {
    GlobalLogger().i(
      'TelnyxClient._onMessage: RAW WebSocket data received: ${data?.toString().trim()}',
    );

    if (data != null) {
      if (data.toString().trim().isNotEmpty) {
        GlobalLogger().i(
          'TxSocket :: ${data.toString().trim()}',
        );
        if (data.toString().trim().contains('error')) {
          final errorJson = jsonEncode(data.toString());
          _logger.log(
            LogLevel.info,
            'Received WebSocket message - Contains Error :: $errorJson',
          );
          try {
            final Map<String, dynamic> jsonData = jsonDecode(data.toString());

            // Extract error code if available
            int? errorCode;
            if (jsonData.containsKey('error') &&
                jsonData['error'] is Map<String, dynamic> &&
                jsonData['error'].containsKey('code')) {
              errorCode = jsonData['error']['code'] as int?;
            }

            final ReceivedResult errorResult = ReceivedResult.fromJson(
              jsonData,
            );

            // Create error with code if available
            final TelnyxSocketError error = TelnyxSocketError(
              errorCode: errorCode ?? 0,
              errorMessage: errorResult.error?.errorMessage ?? 'Unknown error',
            );

            onSocketErrorReceived.call(error);
          } on Exception catch (e) {
            GlobalLogger().e('Error parsing JSON: $e');
          }
        }

        //Login success
        if (data.toString().trim().contains('result')) {
          final paramJson = jsonEncode(data.toString());
          _logger.log(
            LogLevel.info,
            'Received WebSocket message - Contains Result :: $paramJson',
          );

          try {
            final ReceivedResult stateMessage = ReceivedResult.fromJson(
              jsonDecode(data.toString()),
            );

            final mainMessage = ReceivedMessage(
              jsonrpc: stateMessage.jsonrpc,
              method: SocketMethod.gatewayState,
              stateParams: stateMessage.resultParams?.stateParams,
            );

            if (stateMessage.resultParams != null) {
              switch (stateMessage.resultParams?.stateParams?.state) {
                case GatewayState.reged:
                  {
                    if (!_registered) {
                      GlobalLogger().i(
                        'GATEWAY REGISTERED :: ${stateMessage.toString()}',
                      );
                      _invalidateGatewayResponseTimer();
                      _resetGatewayCounters();
                      gatewayState = GatewayState.reged;
                      _waitingForReg = false;
                      final message = TelnyxMessage(
                        socketMethod: SocketMethod.clientReady,
                        message: mainMessage,
                      );
                      onSocketMessageReceived.call(message);
                      if (_isCallFromPush) {
                        //sending attach Call
                        final String platform =
                            defaultTargetPlatform == TargetPlatform.android
                                ? 'android'
                                : 'ios';
                        const String pushEnvironment =
                            kDebugMode ? 'development' : 'production';
                        final AttachCallMessage attachCallMessage =
                            AttachCallMessage(
                          method: SocketMethod.attachCall,
                          id: const Uuid().v4(),
                          params: Params(
                            userVariables: <dynamic, dynamic>{
                              'push_notification_environment': pushEnvironment,
                              'push_notification_provider': platform,
                            },
                          ),
                          jsonrpc: '2.0',
                        );
                        GlobalLogger().i(
                          'attachCallMessage :: ${attachCallMessage.toJson()}',
                        );
                        txSocket.send(jsonEncode(attachCallMessage));
                        _isCallFromPush = false;
                        _pushMetaData = null;
                        clearPushMetaData();
                      }
                      _registered = true;
                    }
                    break;
                  }
                case GatewayState.failed:
                  {
                    GlobalLogger().i(
                      'GATEWAY REGISTRATION FAILED :: ${stateMessage.toString()}',
                    );
                    gatewayState = GatewayState.failed;
                    _invalidateGatewayResponseTimer();
                    final error = TelnyxSocketError(
                      errorCode: TelnyxErrorConstants.gatewayFailedErrorCode,
                      errorMessage: TelnyxErrorConstants.gatewayFailedError,
                    );
                    onSocketErrorReceived(error);
                    break;
                  }
                case GatewayState.unreged:
                  {
                    GlobalLogger().i(
                      'GATEWAY UNREGED :: ${stateMessage.toString()}',
                    );
                    gatewayState = GatewayState.unreged;
                    break;
                  }
                case GatewayState.register:
                  {
                    _logger.log(
                      LogLevel.info,
                      'GATEWAY REGISTERING :: ${stateMessage.toString()}',
                    );
                    gatewayState = GatewayState.register;
                    break;
                  }
                case GatewayState.unregister:
                  {
                    GlobalLogger().i(
                      'GATEWAY UNREGISTERED :: ${stateMessage.toString()}',
                    );
                    gatewayState = GatewayState.unregister;
                    break;
                  }
                case GatewayState.attached:
                  {
                    GlobalLogger().i(
                      'GATEWAY ATTACHED :: ${stateMessage.toString()}',
                    );
                    break;
                  }
                default:
                  {
                    GlobalLogger().i('$stateMessage');
                  }
              }
            }
          } on Exception catch (e) {
            GlobalLogger().e('Error parsing JSON: $e');
          }
        } else if (data.toString().trim().contains('method')) {
          //Received Telnyx Method Message
          final messageJson = jsonDecode(data.toString());

          final ReceivedMessage clientReadyMessage = ReceivedMessage.fromJson(
            jsonDecode(data.toString()),
          );
          if (clientReadyMessage.voiceSdkId != null) {
            GlobalLogger().i('VoiceSdkID :: ${clientReadyMessage.voiceSdkId}');
            _pushMetaData = PushMetaData(
              callerNumber: null,
              callerName: null,
              voiceSdkId: clientReadyMessage.voiceSdkId,
            );
          } else {
            GlobalLogger().e('VoiceSdkID not found');
          }
          GlobalLogger().i(
            'Received WebSocket message - Contains Method :: $messageJson',
          );
          switch (messageJson['method']) {
            case SocketMethod.ping:
              {
                final result = Result(message: 'PONG', sessid: sessid);
                final pongMessage = PongMessage(
                  jsonrpc: JsonRPCConstant.jsonrpc,
                  id: const Uuid().v4(),
                  result: result,
                );
                final String jsonPongMessage = jsonEncode(pongMessage);
                txSocket.send(jsonPongMessage);
                break;
              }
            case SocketMethod.clientReady:
              {
                if (gatewayState != GatewayState.reged) {
                  GlobalLogger().i('Retrieving Gateway state...');
                  if (_waitingForReg) {
                    _requestGatewayStatus();
                    _gatewayResponseTimer = Timer(
                      Duration(milliseconds: Constants.gatewayResponseDelay),
                      () {
                        if (_registrationRetryCounter <
                            Constants.retryRegisterTime) {
                          if (_waitingForReg) {
                            _onMessage(data);
                          }
                          _registrationRetryCounter++;
                        } else {
                          GlobalLogger().i('GATEWAY REGISTRATION TIMEOUT');
                          final error = TelnyxSocketError(
                            errorCode:
                                TelnyxErrorConstants.gatewayTimeoutErrorCode,
                            errorMessage:
                                TelnyxErrorConstants.gatewayTimeoutError,
                          );
                          onSocketErrorReceived(error);
                        }
                      },
                    );
                  }
                } else {
                  final ReceivedMessage clientReadyMessage =
                      ReceivedMessage.fromJson(jsonDecode(data.toString()));
                  final message = TelnyxMessage(
                    socketMethod: SocketMethod.clientReady,
                    message: clientReadyMessage,
                  );
                  onSocketMessageReceived.call(message);
                }
                break;
              }
            case SocketMethod.invite:
              {
                GlobalLogger().i('INCOMING INVITATION :: $messageJson');
                final ReceivedMessage invite = ReceivedMessage.fromJson(
                  jsonDecode(data.toString()),
                );
                final message = TelnyxMessage(
                  socketMethod: SocketMethod.invite,
                  message: invite,
                );

                final Call offerCall = _createCall()
                  ..callId = invite.inviteParams?.callID;
                updateCall(offerCall);

                onSocketMessageReceived.call(message);

                offerCall.callHandler.changeState(CallState.ringing);
                if (!_pendingAnswerFromPush) {
                  offerCall.playRingtone(_ringtonePath);
                  offerCall.callHandler.changeState(CallState.ringing);
                } else {
                  // Cancel the pending answer timeout since INVITE arrived
                  _cancelPendingAnswerTimeout();

                  offerCall.acceptCall(
                    invite.inviteParams!,
                    invite.inviteParams!.calleeIdName ?? '',
                    invite.inviteParams!.callerIdNumber ?? '',
                    'State',
                  );
                  _pendingAnswerFromPush = false;
                  offerCall.callHandler.changeState(CallState.connecting);
                }
                if (_pendingDeclineFromPush) {
                  offerCall.endCall();
                  offerCall.callHandler.changeState(CallState.done);
                  _pendingDeclineFromPush = false;
                }
                break;
              }
            case SocketMethod.attach:
              {
                GlobalLogger().i('ATTACH RECEIVED :: $messageJson');
                final ReceivedMessage invite = ReceivedMessage.fromJson(
                  jsonDecode(data.toString()),
                );
                final message = TelnyxMessage(
                  socketMethod: SocketMethod.attach,
                  message: invite,
                );
                //play ringtone for web
                final Call offerCall = _createCall()
                  ..callId = invite.inviteParams?.callID;
                updateCall(offerCall);

                onSocketMessageReceived.call(message);

                offerCall.acceptCall(
                  invite.inviteParams!,
                  invite.inviteParams!.calleeIdName ?? '',
                  invite.inviteParams!.callerIdNumber ?? '',
                  'State',
                  isAttach: true,
                );
                // Cancel the pending answer timeout since ATTACH arrived
                _cancelPendingAnswerTimeout();
                _pendingAnswerFromPush = false;
                break;
              }
            case SocketMethod.media:
              {
                GlobalLogger().i('MEDIA RECEIVED :: $messageJson');
                final ReceivedMessage mediaReceived = ReceivedMessage.fromJson(
                  jsonDecode(data.toString()),
                );
                if (mediaReceived.inviteParams?.sdp != null) {
                  final Call? mediaCall =
                      calls[mediaReceived.inviteParams?.callID];
                  if (mediaCall == null) {
                    GlobalLogger().d(
                      'Error : Call  is null from Media Message',
                    );
                    _sendNoCallError();
                    return;
                  }
                  mediaCall.onRemoteSessionReceived(
                    mediaReceived.inviteParams?.sdp,
                  );
                  _earlySDP = true;
                } else {
                  GlobalLogger().d('No SDP contained within Media Message');
                }
                break;
              }
            case SocketMethod.answer:
              {
                GlobalLogger().i('INVITATION ANSWERED :: $messageJson');
                final ReceivedMessage inviteAnswer = ReceivedMessage.fromJson(
                  jsonDecode(data.toString()),
                );
                final Call? answerCall =
                    calls[inviteAnswer.inviteParams?.callID];
                if (answerCall == null) {
                  GlobalLogger().d('Error : Call  is null from Answer Message');
                  _sendNoCallError();
                  return;
                }
                final message = TelnyxMessage(
                  socketMethod: SocketMethod.answer,
                  message: inviteAnswer,
                );
                answerCall.callState = CallState.active;

                updateCall(answerCall);

                if (inviteAnswer.inviteParams?.sdp != null) {
                  answerCall.onRemoteSessionReceived(
                    inviteAnswer.inviteParams?.sdp,
                  );
                  onSocketMessageReceived(message);
                } else if (_earlySDP) {
                  onSocketMessageReceived(message);
                } else {
                  GlobalLogger().d(
                    'No SDP provided for Answer or Media, cannot initialize call',
                  );
                  answerCall.endCall();
                }
                _earlySDP = false;
                answerCall.stopAudio();
                break;
              }
            case SocketMethod.bye:
              {
                GlobalLogger().i('BYE RECEIVED :: $messageJson');

                // Parse the bye message to extract termination details
                final Map<String, dynamic> jsonData = jsonDecode(
                  data.toString(),
                );

                // Try to parse as ReceiveByeMessage first to get detailed termination info
                ReceiveByeMessage? byeMessage;
                CallTerminationReason? terminationReason;

                try {
                  byeMessage = ReceiveByeMessage.fromJson(jsonData);

                  // Extract termination details if available
                  if (byeMessage.params != null) {
                    terminationReason = CallTerminationReason(
                      cause: byeMessage.params?.cause,
                      causeCode: byeMessage.params?.causeCode,
                      sipCode: byeMessage.params?.sipCode,
                      sipReason: byeMessage.params?.sipReason,
                    );

                    GlobalLogger().d(
                      'Call termination reason: $terminationReason',
                    );
                  }
                } catch (e) {
                  GlobalLogger().e('Error parsing bye message: $e');
                }

                // Fall back to ReceivedMessage if ReceiveByeMessage parsing failed
                final ReceivedMessage bye = ReceivedMessage.fromJson(jsonData);
                final String? callId =
                    byeMessage?.params?.callID ?? bye.inviteParams?.callID;

                final Call? byeCall = calls[callId];
                if (byeCall == null) {
                  GlobalLogger().d('Error: Call is null from Bye Message');
                  _sendNoCallError();
                  return;
                }

                final message = TelnyxMessage(
                  socketMethod: SocketMethod.bye,
                  message: bye,
                );
                onSocketMessageReceived(message);

                byeCall.stopAudio();
                byeCall.peerConnection?.closeSession();

                // Update call state with termination reason
                byeCall.callHandler.changeState(
                  CallState.done.withTerminationReason(terminationReason),
                );

                calls.remove(byeCall.callId);
                break;
              }
            case SocketMethod.ringing:
              {
                GlobalLogger().i('RINGING RECEIVED :: $messageJson');
                final ReceivedMessage ringing = ReceivedMessage.fromJson(
                  jsonDecode(data.toString()),
                );
                final Call? ringingCall = calls[ringing.inviteParams?.callID];
                if (ringingCall == null) {
                  GlobalLogger().d(
                    'Error : Call  is null from Ringing Message',
                  );
                  _sendNoCallError();
                  return;
                }

                // Send ringing acknowledgement
                final ringingAckResult = RingingAckResult(
                  method: SocketMethod.ringing,
                );
                final ringingAckMessage = RingingAckMessage(
                  jsonrpc: JsonRPCConstant.jsonrpc,
                  id: ringing.id,
                  result: ringingAckResult,
                );
                final String jsonRingingAckMessage =
                    jsonEncode(ringingAckMessage);
                GlobalLogger().i(
                    'Sending ringing acknowledgement: $jsonRingingAckMessage');
                txSocket.send(jsonRingingAckMessage);

                GlobalLogger().i(
                  'Telnyx Leg ID :: ${ringing.inviteParams?.telnyxLegId.toString()}',
                );
                final message = TelnyxMessage(
                  socketMethod: SocketMethod.ringing,
                  message: ringing,
                );
                onSocketMessageReceived(message);
                break;
              }
            case SocketMethod.aiConversation:
              {
                GlobalLogger().i('AI CONVERSATION RECEIVED :: $messageJson');
                final ReceivedMessage aiConversation = ReceivedMessage.fromJson(
                  jsonDecode(data.toString()),
                );

                // Store widget settings if available
                if (aiConversation.aiConversationParams?.widgetSettings !=
                    null) {
                  _currentWidgetSettings =
                      aiConversation.aiConversationParams!.widgetSettings;
                  GlobalLogger().i('Widget settings updated');
                }

                // Process message for transcript extraction
                _processAiConversationForTranscript(aiConversation.aiConversationParams);

                final message = TelnyxMessage(
                  socketMethod: SocketMethod.aiConversation,
                  message: aiConversation,
                );
                onSocketMessageReceived(message);
                break;
              }
          }
        } else {
          GlobalLogger().i('Received and ignored empty packet');
        }
      } else {
        GlobalLogger().i('Received and ignored empty packet');
      }
    }
  }

  /// Process AI conversation messages for transcript extraction
  void _processAiConversationForTranscript(AiConversationParams? params) {
    if (params?.type == null) return;

    switch (params!.type) {
      case 'conversation.item.created':
        _handleConversationItemCreated(params);
        break;
      case 'response.text.delta':
        _handleResponseTextDelta(params);
        break;
      default:
        // Other AI conversation message types are ignored for transcript
        break;
    }
  }

  /// Handle user speech transcript from conversation.item.created messages
  void _handleConversationItemCreated(AiConversationParams params) {
    if (params.item?.role != 'user' || params.item?.status != 'completed') {
      return; // Only handle completed user messages
    }

    final content = params.item?.content
        ?.where((c) => c.transcript != null)
        .map((c) => c.transcript!)
        .join(' ') ?? '';

    if (content.isNotEmpty && params.item?.id != null) {
      final transcriptItem = TranscriptItem(
        id: params.item!.id!,
        role: 'user',
        content: content,
        timestamp: DateTime.now(),
      );

      _transcript.add(transcriptItem);
      onTranscriptUpdate?.call(List.unmodifiable(_transcript));
    }
  }

  /// Handle AI response text deltas from response.text.delta messages
  void _handleResponseTextDelta(AiConversationParams params) {
    if (params.delta == null || params.itemId == null) return;

    final itemId = params.itemId!;
    final delta = params.delta!;

    // Initialize buffer for this response if not exists
    _assistantResponseBuffers.putIfAbsent(itemId, () => StringBuffer());
    _assistantResponseBuffers[itemId]!.write(delta);

    // Create or update transcript item for this response
    final existingIndex = _transcript.indexWhere((item) => item.id == itemId);
    final currentContent = _assistantResponseBuffers[itemId]!.toString();

    if (existingIndex >= 0) {
      // Update existing transcript item with accumulated content
      _transcript[existingIndex] = TranscriptItem(
        id: itemId,
        role: 'assistant',
        content: currentContent,
        timestamp: _transcript[existingIndex].timestamp,
      );
    } else {
      // Create new transcript item
      final transcriptItem = TranscriptItem(
        id: itemId,
        role: 'assistant',
        content: currentContent,
        timestamp: DateTime.now(),
      );
      _transcript.add(transcriptItem);
    }

    onTranscriptUpdate?.call(List.unmodifiable(_transcript));
  }

  void _sendNoCallError() {
    final error = TelnyxSocketError(
      errorCode: 404,
      errorMessage: TelnyxErrorConstants.callNotFound,
    );
    onSocketErrorReceived(error);
  }

  void _requestGatewayStatus() {
    if (_waitingForReg) {
      const uuid = Uuid();
      final gatewayRequestParams = GatewayRequestStateParams();
      final gatewayRequestMessage = GatewayRequestMessage(
        id: uuid.toString(),
        method: SocketMethod.gatewayState,
        params: gatewayRequestParams,
        jsonrpc: JsonRPCConstant.jsonrpc,
      );

      final String jsonGatewayRequestMessage = jsonEncode(
        gatewayRequestMessage,
      );

      txSocket.send(jsonGatewayRequestMessage);
    }
  }

  void _invalidateGatewayResponseTimer() {
    _gatewayResponseTimer?.cancel();
  }

  void _resetGatewayCounters() {
    _registrationRetryCounter = 0;
    _connectRetryCounter = 0;
    _waitingForReg = true;
    gatewayState = GatewayState.idle;
  }
}
