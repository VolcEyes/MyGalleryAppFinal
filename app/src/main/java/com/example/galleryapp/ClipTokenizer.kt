package com.example.galleryapp

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

class ClipTokenizer(context: Context) {
    private val vocab = mutableMapOf<String, Int>()

    // CLIP specific special tokens
    private val bosToken = 49406 // Beginning of sequence
    private val eosToken = 49407 // End of sequence
    private val padToken = 0     // Padding
    private val maxSeqLen = 77   // MobileCLIP strictly requires 77 length

    init {
        // Load and decompress the GZIP vocabulary from the assets folder
        try {
            val assetManager = context.assets
            // 1. Open the .gz file
            val inputStream = assetManager.open("bpe_simple_vocab_16e6.txt.gz")
            // 2. Wrap it in a GZIPInputStream to decompress it on the fly
            val gzipInputStream = GZIPInputStream(inputStream)
            // 3. Read it as normal text
            val reader = BufferedReader(InputStreamReader(gzipInputStream, "UTF-8"))

            var line: String?
            var index = 0
            while (reader.readLine().also { line = it } != null) {
                // The BPE vocab sometimes has spaces in the string, split carefully
                val token = line!!.split(" ")[0]
                vocab[token] = index
                index++
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun tokenize(text: String): LongArray {
        val tokens = mutableListOf<Int>()
        tokens.add(bosToken)

        // 1. Clean the text (lowercase, remove heavy punctuation)
        val words = text.lowercase().replace(Regex("[^a-z0-9\\s]"), "").split("\\s+".toRegex())

        // 2. Simplified Byte-Pair Encoding (BPE) Greedy Tokenization
        for (word in words) {
            if (word.isBlank()) continue

            // Standard CLIP format denotes the end of a word with </w>
            val bpeWord = "$word</w>"
            var remaining = bpeWord

            while (remaining.isNotEmpty()) {
                var match = ""
                var matchId = -1

                // Find the longest matching prefix in our vocabulary dictionary
                for (i in remaining.length downTo 1) {
                    val sub = remaining.substring(0, i)
                    if (vocab.containsKey(sub)) {
                        match = sub
                        matchId = vocab[sub]!!
                        break
                    }
                }

                if (matchId != -1) {
                    tokens.add(matchId)
                    remaining = remaining.substring(match.length)
                } else {
                    // Skip unknown characters if they aren't in the vocab
                    remaining = remaining.substring(1)
                }
            }
        }

        tokens.add(eosToken)

        // 3. Pad or truncate the sequence to exactly 77 tokens
        val result = LongArray(maxSeqLen) { padToken.toLong() }
        for (i in 0 until minOf(tokens.size, maxSeqLen)) {
            result[i] = tokens[i].toLong()
        }

        if (tokens.size > maxSeqLen) {
            result[maxSeqLen - 1] = eosToken.toLong()
        }

        return result
    }
}