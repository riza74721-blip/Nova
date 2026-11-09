package com.nova.assistant.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArithmeticTest {
    @Test fun multiplicationHasPrecedenceAndParenthesesChangeIt() {
        assertEquals(Arithmetic.Result.Success("14"), Arithmetic.evaluate("2 + 3 * 4"))
        assertEquals(Arithmetic.Result.Success("20"), Arithmetic.evaluate("(2 + 3) * 4"))
    }

    @Test fun understandsDecimalCommaUnaryMinusAndUnicodeOperators() {
        assertEquals(Arithmetic.Result.Success("-3,75"), Arithmetic.evaluate("-(1,5 + 1) × 3 ÷ 2"))
        assertEquals(Arithmetic.Result.Success("0,3"), Arithmetic.evaluate("0,1 + 0,2"))
    }

    @Test fun smallNonzeroResultsAreNotRoundedToZero() {
        assertEquals(Arithmetic.Result.Success("0,0000000000001"), Arithmetic.evaluate("1 / 10000000000000"))
        assertEquals(Arithmetic.Result.Success("1234567891234567892"), Arithmetic.evaluate("1234567891234567891 + 1"))
    }

    @Test fun divisionByZeroExplainsProblem() {
        val result = Arithmetic.evaluate("7 / (3 - 3)")
        assertTrue(result is Arithmetic.Result.Failure)
        assertTrue((result as Arithmetic.Result.Failure).message.contains("Sıfıra bölme"))
    }

    @Test fun rejectsUnbalancedSyntaxAndScriptInput() {
        listOf("", "(1+2", "1 + 2)", "2 3", "1,2,3", "System.exit(0)", "2 ** 8", "1e8", "1/0; alert(1)").forEach {
            assertTrue("Should reject: $it", Arithmetic.evaluate(it) is Arithmetic.Result.Failure)
        }
    }

    @Test fun boundsExpressionLengthAndNesting() {
        assertTrue(Arithmetic.evaluate("1+".repeat(150) + "1") is Arithmetic.Result.Failure)
        assertTrue(Arithmetic.evaluate("(".repeat(30) + "1" + ")".repeat(30)) is Arithmetic.Result.Failure)
    }
}
