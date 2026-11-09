package com.nova.assistant.brain

import java.math.BigDecimal
import java.math.MathContext

/** A bounded arithmetic grammar; expressions are never passed to a script engine. */
internal object Arithmetic {
    sealed class Result {
        data class Success(val value: String) : Result()
        data class Failure(val message: String) : Result()
    }

    fun evaluate(expression: String): Result = try {
        val prepared = expression.replace(',', '.').replace('×', '*').replace('÷', '/')
        if (prepared.length > 200) throw Problem("İşlem çok uzun. Daha kısa bir işlem söyle.")
        val answer = Parser(prepared).parse()
        val display = answer.stripTrailingZeros().toPlainString().replace('.', ',')
        Result.Success(display)
    } catch (problem: Problem) {
        Result.Failure(problem.message ?: "İşlemi anlayamadım.")
    } catch (_: ArithmeticException) {
        Result.Failure("Bu işlemi hesaplayamadım. Sayıları ve işleçleri kontrol et.")
    }

    private class Problem(message: String) : RuntimeException(message)

    private class Parser(private val input: String) {
        private var index = 0
        private var depth = 0
        private val math = MathContext.DECIMAL64

        fun parse(): BigDecimal {
            val result = expression()
            skipSpace()
            if (index != input.length) invalid()
            return result
        }

        private fun expression(): BigDecimal {
            var result = term()
            while (true) {
                result = when {
                    consume('+') -> bounded(result.add(term()))
                    consume('-') -> bounded(result.subtract(term()))
                    else -> return result
                }
            }
        }

        private fun term(): BigDecimal {
            var result = factor()
            while (true) {
                result = when {
                    consume('*') -> bounded(result.multiply(factor()))
                    consume('/') -> {
                        val divisor = factor()
                        if (divisor.compareTo(BigDecimal.ZERO) == 0) throw Problem("Sıfıra bölme tanımsızdır. Başka bir işlem deneyelim.")
                        bounded(result.divide(divisor, math))
                    }
                    else -> return result
                }
            }
        }

        private fun factor(): BigDecimal {
            if (++depth > 16) throw Problem("İşlem çok iç içe. Daha kısa bir işlem söyle.")
            try {
                if (consume('+')) return factor()
                if (consume('-')) return factor().negate()
                if (consume('(')) {
                    val result = expression()
                    if (!consume(')')) invalid()
                    return result
                }
                skipSpace()
                val start = index
                var hasDigit = false
                while (index < input.length && input[index].isDigit()) { index++; hasDigit = true }
                if (index < input.length && input[index] == '.') {
                    index++
                    while (index < input.length && input[index].isDigit()) { index++; hasDigit = true }
                }
                if (!hasDigit || index - start > 40) invalid()
                return bounded(input.substring(start, index).toBigDecimalOrNull() ?: invalid())
            } finally { depth-- }
        }

        private fun bounded(value: BigDecimal): BigDecimal {
            if (value.precision() > 60 || value.abs() > BigDecimal("1E+50")) {
                throw Problem("Bu sayı çok büyük. Daha küçük sayılarla deneyelim.")
            }
            return value
        }

        private fun consume(character: Char): Boolean {
            skipSpace()
            if (index < input.length && input[index] == character) { index++; return true }
            return false
        }

        private fun skipSpace() { while (index < input.length && input[index].isWhitespace()) index++ }
        private fun invalid(): Nothing = throw Problem("İşlemi anlayamadım. Örneğin: hesapla (12 + 8) / 2. Ondalık sayılar için virgül kullanabilirsin.")
    }
}
