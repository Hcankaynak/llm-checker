package io.llmchecker

import java.util.Random

private val VOCAB = (
    "system model request token cache latency batch kernel memory stream buffer thread schema client server " +
        "service module queue worker cluster policy config deploy build test release version branch commit review " +
        "function method object class interface package library network socket packet protocol gateway timeout retry " +
        "error warning result value index table column record query filter sort merge split parse render compile " +
        "bridge window signal vector matrix tensor weight layer attention prefill decode sample logit prompt context " +
        "window session user agent tool command output input file path directory source target default option flag " +
        "monitor metric counter gauge histogram trace span event handler callback promise future task job pipeline " +
        "storage volume backup restore archive snapshot cluster region zone node pod container image registry secret"
    ).split(" ")

class PromptBuilder(
    private val tokensPerWord: Double,
    private val overheadTokens: Int,
    private val runNonce: String,
    private val sharedPrefixTokens: Int,
    private val totalPromptTokens: Int,
    private val outputTokens: Int,
    seed: Long,
) {
    private val seedBase = seed

    private fun wordsFor(tokens: Int): Int = maxOf(0, ((tokens - overheadTokens) / tokensPerWord).toInt())

    private fun words(count: Int, rng: Random): String {
        val sb = StringBuilder(count * 7)
        repeat(count) {
            if (it > 0) sb.append(' ')
            sb.append(VOCAB[rng.nextInt(VOCAB.size)])
        }
        return sb.toString()
    }

    private val prefix: String by lazy {
        if (sharedPrefixTokens <= 0) ""
        else "Session $runNonce. Shared project reference material follows.\n" +
            words(wordsFor(sharedPrefixTokens), Random(seedBase)) + "\n\n"
    }

    fun build(requestIndex: Int): String {
        val suffixTokens = totalPromptTokens - sharedPrefixTokens
        val rng = Random(seedBase * 31 + requestIndex + 1)
        val head = if (sharedPrefixTokens > 0) prefix else "Session $runNonce request $requestIndex.\n"
        val body = if (suffixTokens > 0) words(wordsFor(suffixTokens), rng) else ""
        return head + body +
            "\n\nTask: write a long, detailed technical essay about the material above. " +
            "Keep writing in plain prose and do not stop early (target about $outputTokens tokens)."
    }

    companion object {
        fun probeText(words: Int, nonce: String, seed: Long): String {
            val rng = Random(seed)
            val sb = StringBuilder("Calibration $nonce.\n")
            repeat(words) {
                if (it > 0) sb.append(' ')
                sb.append(VOCAB[rng.nextInt(VOCAB.size)])
            }
            return sb.toString()
        }
    }
}
