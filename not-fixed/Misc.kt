package dorkbox.network.other

import kotlin.math.ceil

/**
 *
 */
object Misc {

    private fun annotations() {
        //    internal val classesWithRmiFields = IdentityMap<Class<*>, Array<Field>>()
//        // get all classes that have fields with @Rmi field annotation.
//        // THESE classes must be customized with our special RmiFieldSerializer serializer so that the @Rmi field is properly handled
//
//        // SPECIFICALLY, these fields must also be an IFACE for the field type!
//
//        // NOTE: The @Rmi field type will already have to be a registered type with kryo!
//        // we can use this information on WHERE to scan for classes.
//        val filesToScan = mutableSetOf<File>()
//
//        classesToRegister.forEach { registration ->
//            val clazz = registration.clazz
//
//            // can't do anything if codeSource is null!
//            val codeSource = clazz.protectionDomain.codeSource ?: return@forEach
//            // file:/Users/home/java/libs/xyz-123.jar
//            // file:/projects/classes
//            val jarOrClassPath = codeSource.location.toString()
//
//            if (jarOrClassPath.endsWith(".jar")) {
//                val fileName: String = URLDecoder.decode(jarOrClassPath.substring("file:".length), Charset.defaultCharset())
//                filesToScan.add(File(fileName).absoluteFile)
//            } else {
//                val classPath: String = URLDecoder.decode(jarOrClassPath.substring("file:".length), Charset.defaultCharset())
//                filesToScan.add(File(classPath).absoluteFile)
//            }
//        }
//
//        val toTypedArray = filesToScan.toTypedArray()
//        if (logger.isTraceEnabled) {
//            toTypedArray.forEach {
//                logger.trace { "Adding location to annotation scanner: $it"}
//            }
//        }
//
//
//
//        // now scan these jars/directories
//        val fieldsWithRmiAnnotation = AnnotationDetector.scanFiles(*toTypedArray)
//            .forAnnotations(Rmi::class.java)
//            .on(ElementType.FIELD)
//            .collect { cursor -> Pair(cursor.type, cursor.field!!) }
//
//        // have to make sure that the field type is specified as an interface (and not an implementation)
//        fieldsWithRmiAnnotation.forEach { pair ->
//            require(pair.second.type.isInterface) { "@Rmi annotated fields must be an interface!" }
//        }
//
//        if (fieldsWithRmiAnnotation.isNotEmpty()) {
//            logger.info { "Verifying scanned classes containing @Rmi field annotations" }
//        }
//
//        // have to put this in a map, so we can quickly lookup + get the fields later on.
//        // NOTE: a single class can have MULTIPLE fields with @Rmi annotations!
//        val rmiAnnotationMap = IdentityMap<Class<*>, MutableList<Field>>()
//        fieldsWithRmiAnnotation.forEach {
//            var fields = rmiAnnotationMap[it.first]
//            if (fields == null) {
//                fields = mutableListOf()
//            }
//
//            fields.add(it.second)
//            rmiAnnotationMap.put(it.first, fields)
//        }
//
//        // now make it an array for fast lookup for the [parent class] -> [annotated fields]
//        rmiAnnotationMap.forEach {
//            classesWithRmiFields.put(it.key, it.value.toTypedArray())
//        }
//
//        // this will set up the class registration information
//        initKryo()
//
//        // now everything is REGISTERED, possibly with custom serializers, we have to go back and change them to use our RmiFieldSerializer
//        fieldsWithRmiAnnotation.forEach FIELD_SCAN@{ pair ->
//            // the parent class must be an IMPL. The reason is that THIS FIELD will be sent as a RMI object, and this can only
//            // happen on objects that exist
//
//            // NOTE: it IS necessary for the rmi-client to be aware of the @Rmi annotation (because it also has to have the correct serialization)
//
//            // also, it is possible for the class that has the @Rmi field to be a NORMAL object (and not an RMI object)
//            // this means we found the registration for the @Rmi field annotation
//
//            val parentRmiRegistration = classesToRegister.firstOrNull { it is ClassRegistrationForRmi && it.implClass == pair.first}
//
//
//            // if we have a parent-class registration, this means we are the rmi-server
//            //
//            // AND BECAUSE OF THIS
//            //
//            // we must also have the field type registered as RMI
//            if (parentRmiRegistration != null) {
//                // rmi-server
//
//                // is the field type registered also?
//                val fieldRmiRegistration = classesToRegister.firstOrNull { it.clazz == pair.second.type}
//                require(fieldRmiRegistration is ClassRegistrationForRmi) { "${pair.second.type} is not registered for RMI! Unable to continue"}
//
//                logger.trace { "Found @Rmi field annotation '${pair.second.type}' in class '${pair.first}'" }
//            } else {
//                // rmi-client
//
//                // NOTE: rmi-server MUST have the field IMPL registered (ie: via RegisterRmi)
//                //       rmi-client will have the serialization updated from the rmi-server during connection handshake
//            }
//        }
    }


    /**
     * Split array into chunks, max of 256 chunks.
     * byte[0] = chunk ID
     * byte[1] = total chunks (0-255) (where 0->1, 2->3, 127->127 because this is indexed by a byte)
     */
    private fun divideArray(source: ByteArray, chunksize: Int): Array<ByteArray>? {
        val fragments = ceil(source.size / chunksize.toDouble()).toInt()
        if (fragments > 127) {
            // cannot allow more than 127
            return null
        }

        // pre-allocate the memory
        val splitArray = Array(fragments) { ByteArray(chunksize + 2) }
        var start = 0
        for (i in splitArray.indices) {
            var length = if (start + chunksize > source.size) {
                source.size - start
            } else {
                chunksize
            }
            splitArray[i] = ByteArray(length + 2)
            splitArray[i][0] = i.toByte() // index
            splitArray[i][1] = fragments.toByte() // total number of fragments
            System.arraycopy(source, start, splitArray[i], 2, length)
            start += chunksize
        }
        return splitArray
    }
}

//    fun initClassRegistration(channel: Channel, registration: Registration): Boolean {
//        val details = serialization.getKryoRegistrationDetails()
//        val length = details.size
//        if (length > Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE) {
//            // it is too large to send in a single packet
//
//            // child arrays have index 0 also as their 'index' and 1 is the total number of fragments
//            val fragments = divideArray(details, Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE)
//            if (fragments == null) {
//                logger.error("Too many classes have been registered for Serialization. Please report this issue")
//                return false
//            }
//            val allButLast = fragments.size - 1
//            for (i in 0 until allButLast) {
//                val fragment = fragments[i]
//                val fragmentedRegistration = Registration.hello(registration.oneTimePad, config.settingsStore.getPublicKey())
//                fragmentedRegistration.payload = fragment
//
//                // tell the server we are fragmented
//                fragmentedRegistration.upgradeType = UpgradeType.FRAGMENTED
//
//                // tell the server we are upgraded (it will bounce back telling us to connect)
//                fragmentedRegistration.upgraded = true
//                channel.writeAndFlush(fragmentedRegistration)
//            }
//
//            // now tell the server we are done with the fragments
//            val fragmentedRegistration = Registration.hello(registration.oneTimePad, config.settingsStore.getPublicKey())
//            fragmentedRegistration.payload = fragments[allButLast]
//
//            // tell the server we are fragmented
//            fragmentedRegistration.upgradeType = UpgradeType.FRAGMENTED
//
//            // tell the server we are upgraded (it will bounce back telling us to connect)
//            fragmentedRegistration.upgraded = true
//            channel.writeAndFlush(fragmentedRegistration)
//        } else {
//            registration.payload = details
//
//            // tell the server we are upgraded (it will bounce back telling us to connect)
//            registration.upgraded = true
//            channel.writeAndFlush(registration)
//        }
//        return true
//    }


