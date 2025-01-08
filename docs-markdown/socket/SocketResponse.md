## SocketResponse

Data class used with communication between socket connection and TelnyxClient.

```kotlin
data class SocketResponse<out T>(var status: SocketStatus, val data: T?, val errorMessage: String?)
```

Where SocketStatus is a Enum class:

```kotlin
enum class SocketStatus {
ESTABLISHED,
MESSAGERECEIVED,
ERROR,
LOADING,
DISCONNECT
}
```

The SocketStatus can be one of the following
* ESTABLISHED a connection to the socket has been established
* MESSAGERECEIVED the socket has received a message
* ERROR the socket has encountered an error
* LOADING the socket is loading a connection
* DISCONNECT when the socket is disconnect

  