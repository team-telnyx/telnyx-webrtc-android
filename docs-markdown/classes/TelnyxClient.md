## Telnyx Client
NOTE:
Remember to add and handle INTERNET, RECORD_AUDIO and ACCESS_NETWORK_STATE permissions

   <p align="center">
               <img align="center" src="https://user-images.githubusercontent.com/9112652/117322479-f4731c00-ae85-11eb-9259-6333fc20b629.png">
            </p>

### Initialize
To initialize the TelnyxClient you will have to provide the application context. 

```kotlin
  telnyxClient = TelnyxClient(context)
```

### Connect
Once an instance is created, you can call the one of two available .connect(....) method to connect to the socket.

```kotlin
fun connect(
    providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
    credentialConfig: CredentialConfig,
    txPushMetaData: String? = null,
    autoLogin: Boolean = true,
)
```
Connects to the socket by credential and using this client as the listener. Will respond with 'No Network Connection' if there is no network available
Parameters:
* providedServerConfig, the TxServerConfiguration used to connect to the socket
* txPushMetaData, the push metadata used to connect to a call from push (Get this from push notification - fcm data payload) required fot push calls to work
* credentialConfig, represents a SIP user for login - credential based
* autoLogin, if true, the SDK will automatically log in with the provided credentials on connection established. We recommend setting this to true.


```kotlin
fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        tokenConfig: TokenConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true,
    )
```
Connects to the socket by token and using this client as the listener. Will respond with 'No Network Connection' if there is no network available
* providedServerConfig, the TxServerConfiguration used to connect to the socket
* txPushMetaData, the push metadata used to connect to a call from push (Get this from push notification - fcm data payload) required fot push calls to work
* tokenConfig, represents a SIP user for login - token based
* autoLogin, if true, the SDK will automatically log in with the provided credentials on connection established. We recommend setting this to true.

**Note:** **tokenConfig** and **credentialConfig** are data classes that represent login settings for the client to use. They belong to **TelnyxConfig** and can be found in Data part.

### Listening for events and reacting
We need to react for a socket connection state or incoming calls. We do this by getting the Telnyx Socket Response callbacks from our TelnyxClient.

```kotlin
fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>> = socketResponseLiveData
```
Returns the socket response in the form of LiveData. The format of each message is provided in `SocketResponse` and `ReceivedMessageBody`.
* @see [SocketResponse]
* @see [ReceivedMessageBody]

Response can be observed by implementation of abstract class `SocketObserver`.
In `onMessageReceived` we will receive objects of `ReceivedMessageBody` class.

```kotlin
abstract class SocketObserver<T> : Observer<SocketResponse<T>> {

    abstract fun onConnectionEstablished()
    abstract fun onMessageReceived(data: T?)
    abstract fun onLoading()
    abstract fun onError(message: String?)
    abstract fun onSocketDisconnect()

    override fun onChanged(value: SocketResponse<T>) {
        when (value.status) {
            SocketStatus.ESTABLISHED -> onConnectionEstablished()
            SocketStatus.MESSAGERECEIVED -> onMessageReceived(value.data)
            SocketStatus.LOADING -> onLoading()
            SocketStatus.ERROR -> onError(value.errorMessage)
            SocketStatus.DISCONNECT -> onSocketDisconnect()
        }
    }
}
```

