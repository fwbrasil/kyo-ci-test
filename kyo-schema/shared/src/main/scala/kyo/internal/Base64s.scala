package kyo.internal

/** Base64 decoding that bounds its result to the byte length implied by the input text instead of trusting the length of the array the
  * decoder returns.
  *
  * On scala-native 0.5.12 a concurrent heap-corruption defect can clobber a small array's length-header word between allocation and first
  * read (see NATIVE_HEAP_CORRUPTION_DIAGNOSIS.md). When the clobber lands on the decoder's output array, a genuinely 3-byte decode reads
  * back as hundreds of bytes of adjacent heap; the over-read then propagates faithfully through any length-driven copy. The decoded length
  * of any string the basic decoder accepts is fully determined by the text (each non-pad character carries 6 bits), so recompute it and
  * trim when the returned array disagrees. On the JVM and JS, where the decoder is well-behaved, the trim branch is never taken and behavior
  * is identical.
  */
private[kyo] object Base64s:

    /** Base64-decodes `value` and returns an array whose length is the text-implied decoded length. Throws `IllegalArgumentException` for
      * invalid input, exactly as the underlying decoder does (callers keep their existing catch).
      */
    def decodeExact(value: String): Array[Byte] =
        val decoded  = java.util.Base64.getDecoder.decode(value)
        val expected = decodedLength(value)
        if decoded.length == expected then decoded
        else java.util.Arrays.copyOf(decoded, expected)
    end decodeExact

    /** The decoded byte length implied by a base64 string the basic decoder accepts: `(chars - padding) * 6 / 8`. Exact for padded,
      * partially padded, unpadded, and empty inputs.
      */
    private def decodedLength(value: String): Int =
        val len = value.length
        val pad =
            if len > 0 && value.charAt(len - 1) == '=' then
                if len > 1 && value.charAt(len - 2) == '=' then 2 else 1
            else 0
        (((len - pad).toLong * 3) / 4).toInt
    end decodedLength

end Base64s
