package dorkbox.network.connection

data class ConnectionParams<C: Connection>(val endPoint: EndPoint<C>, val mediaDriverConnection: MediaDriverConnection,
                            val publicKeyValidation: PublicKeyValidationState)
