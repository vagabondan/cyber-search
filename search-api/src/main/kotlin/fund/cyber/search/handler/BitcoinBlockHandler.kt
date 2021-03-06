package fund.cyber.search.handler

import com.fasterxml.jackson.databind.ObjectMapper
import fund.cyber.dao.bitcoin.BitcoinDaoService
import fund.cyber.node.common.longValue
import fund.cyber.search.context.AppContext
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers

class BitcoinBlockHandler(
        private val bitcoinDaoService: BitcoinDaoService = AppContext.bitcoinDaoService,
        private val jsonSerializer: ObjectMapper = AppContext.jsonSerializer
) : HttpHandler {

    override fun handleRequest(exchange: HttpServerExchange) {

        val blockNumber = exchange.queryParameters["blockNumber"]?.longValue()

        if (blockNumber == null) {
            exchange.statusCode = 400
            return
        }

        val block = bitcoinDaoService.getBlockByNumber(blockNumber)

        if(block == null) {
            exchange.statusCode = 404
            return
        }

        val rawResponse = jsonSerializer.writeValueAsString(block)

        exchange.responseHeaders.put(Headers.CONTENT_TYPE, "application/json")
        exchange.responseSender.send(rawResponse)
    }
}
