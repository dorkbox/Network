package dorkbox.network.other

import org.agrona.collections.Hashing
import org.agrona.collections.Long2ObjectHashMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class RateLimiter(private val logger: Logger,
                  previousAccessTimeInMS: Long, freebieAttempts: Int = 10) {

    companion object {
        private val INVALID_TIME = 0L
        private val RATE_LIMIT_MAX_WAIT_SECONDS = 625 // 0 disables it
        private val RATE_LIMIT_MAX_WAIT_MILLISECONDS = TimeUnit.SECONDS.toMillis(RATE_LIMIT_MAX_WAIT_SECONDS.toLong()) // 0 disables it

        private const val DISABLE_DELAY = false
        private val MAX_ATTEMPTS = sqrt(RATE_LIMIT_MAX_WAIT_SECONDS.toDouble()).toInt() // 600 seconds is 25 attempts This can be approximate

        /**
         * what is the fastest we want people to access the resource?
         */
        private val MAX_ACCESS_RATE_SECONDS = 5


        /**
         * If we want, we can also use a global rate limiter, so we don't have to keep creating them to be used for specific things
         */
        private val globalRateLimits = ConcurrentHashMap<String, RateLimiter>()


        init {
            val listOfTimedInsertions = mutableListOf<Pair<Instant, Long>>()
            val ipToAccessMap = Long2ObjectHashMap<Pair<Int, Instant>>(32, Hashing.DEFAULT_LOAD_FACTOR, false)

            val expirationTime = Instant.now().plusSeconds(10L)

            val ipAddress = 52L
            val accessCount = 2

            listOfTimedInsertions.add(Pair(expirationTime, ipAddress))
            ipToAccessMap.putIfAbsent(ipAddress, Pair(accessCount, expirationTime))


            // reset all login rate limiting at midnight
//            TimerUtil.runAtTwoAM(globalRateLimits::clear)
        }

        fun check(itemToLimit: String): Int {
            // check to see if we have an entry already, and are trying to do something too quickly.
            val accessTimeInMS = System.currentTimeMillis()

            val limiter = globalRateLimits.computeIfAbsent(itemToLimit) { RateLimiter(LoggerFactory.getLogger("RATE"), accessTimeInMS) }
            return limiter.getCurrentDelay(accessTimeInMS)
        }
    }

    private val initialAttemptValue: Int
    private var previousAccessTimeInMS: Long
    private var attempt: Int
    private var wasLastAttemptSuccess = false

    init {
        // during the construction of this object, make sure that we don't lock ourselves out wrt MAX_ACCESS_RATE_SECONDS
        this.previousAccessTimeInMS = previousAccessTimeInMS - TimeUnit.SECONDS.toMillis(MAX_ACCESS_RATE_SECONDS.toLong())

        // start at - 1 - freebieAttempts so the first access we want to start at makes the counter start at 0 when we checkAccessDelay
        initialAttemptValue = 0 - freebieAttempts
        attempt = initialAttemptValue
    }

    /**
     * Increase the attempts by one as long as its not MAX_ATTEMPTS. Return the delay.
     *
     * @return the delay in seconds. 0 means no delay since it is a free attempt.
     */
    @Synchronized
    fun incrementDelay(): Int {
        if (DISABLE_DELAY) {
            return 0
        }
        if (attempt != MAX_ATTEMPTS) {
            attempt++
        }
        logger.debug("Current Attempts: '{}'. Max Attempts: '{}'", attempt, MAX_ATTEMPTS)
        val delayInSeconds = Math.max(MAX_ACCESS_RATE_SECONDS, attemptDelay())
        logger.debug("Delay in Seconds: '{}'", delayInSeconds)
        return delayInSeconds
    }

    /**
     * Check if we are in a delay and return the time remaining in the delay.
     *
     * @return the remaining delay in seconds. 0 means no delay.
     */
    @Synchronized
    fun getCurrentDelay(accessTimeInMS: Long): Int {
        if (DISABLE_DELAY) {
            return 0
        }
        logger.debug("Current Attempts: '{}'. Max Attempts: '{}'", attempt, MAX_ATTEMPTS)
        val delayInSeconds = Math.max(MAX_ACCESS_RATE_SECONDS, attemptDelay())


        logger.debug("Delay in Seconds: '{}'", delayInSeconds)
        val delayInMS = TimeUnit.SECONDS.toMillis(delayInSeconds.toLong())

        // How long since last access.
        val attemptIntervalInMS = accessTimeInMS - previousAccessTimeInMS
        logger.debug("Attempt Interval '{}'", TimeUnit.MILLISECONDS.toSeconds(attemptIntervalInMS))

        return if (attemptIntervalInMS < delayInMS) {
            logger.debug("Triggered rate limiter and incrementing wait time. Current attempt is {}. Resource was accessed {} ms too " +
                    "quickly.", attempt, delayInMS - attemptIntervalInMS)
            val remainingDelayInSeconds = TimeUnit.MILLISECONDS.toSeconds(delayInMS - attemptIntervalInMS).toInt()
            logger.debug("Remaining Delay: '{}'", remainingDelayInSeconds)
            remainingDelayInSeconds
        } else {
            // Set previousAccessTime
            previousAccessTimeInMS = accessTimeInMS

            // Reset the counter when there has been silence for longer than 10 minutes (MAX_WAIT_TIME) after a failure
            if (attemptIntervalInMS > RATE_LIMIT_MAX_WAIT_MILLISECONDS) {
                logger.debug("Triggered rate limiter hard reset on success. (Waited longer than {} seconds", RATE_LIMIT_MAX_WAIT_SECONDS)
                attempt = initialAttemptValue
            }
            logger.debug("Attempt after delay. No remaining delay")

            0
        }
    }

    private fun attemptDelay(): Int {
        return when {
            attempt <= 0 -> {
                0
            }
            attempt in 1..5 -> {
                10
            }
            attempt in 6..10 -> {
                120
            }
            attempt in 11..15 -> {
                240
            }
            attempt in 16..20 -> {
                360
            }
            attempt in 21..24 -> {
                480
            }
            else -> {
                logger.error("Max delay reached! This is probably a hacker.")
                600
            }
        }
    }


    /**
     * Decrements the access counter if there has been a success
     */
    @Synchronized
    fun markSuccess() {
        logger.info("Marked as success.")
        wasLastAttemptSuccess = true

        // if it's a success, then there will never be a wait time.
        if (attempt > initialAttemptValue) {
            attempt--
        }
    }

    /**
     * Marks this as a "success" for a password change. DOES NOT decrement the counter!
     */
    @Synchronized
    fun markSuccessForPassword() {
        wasLastAttemptSuccess = true
    }

    @Synchronized
    fun wasLastAttemptSuccess(): Boolean {
        val wasLastAttemptSuccess = wasLastAttemptSuccess

        // always reset back to false so that we only get 1 "freebie" after a success to attempt to login/etc
        this.wasLastAttemptSuccess = false
        return wasLastAttemptSuccess
    }

    /**
     * Marks this as a "failure" so that it must wait the max wait time
     */
    @Synchronized
    fun markMaxFailure() {
        attempt = MAX_ATTEMPTS
    }
}
