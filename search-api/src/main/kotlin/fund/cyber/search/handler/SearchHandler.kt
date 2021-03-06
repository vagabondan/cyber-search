package fund.cyber.search.handler

import com.fasterxml.jackson.databind.ObjectMapper
import fund.cyber.node.common.intValue
import fund.cyber.node.common.stringValue
import fund.cyber.node.model.DocumentKey
import fund.cyber.node.model.SearchRequestProcessingStats
import fund.cyber.search.configuration.SearchRequestProcessingStatsKafkaProducer
import fund.cyber.search.configuration.SearchRequestProcessingStatsRecord
import fund.cyber.search.context.AppContext
import fund.cyber.search.model.ItemPreview
import fund.cyber.search.model.SearchResponse
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.launch
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.unit.Fuzziness
import org.elasticsearch.index.query.QueryBuilders
import java.time.Instant


class SearchHandler(
        private val jsonSerializer: ObjectMapper = AppContext.jsonSerializer,
        private val elasticClient: TransportClient = AppContext.elasticClient,
        private val kafkaProducer: SearchRequestProcessingStatsKafkaProducer = AppContext.searchRequestProcessingStatsKafkaProducer
) : HttpHandler {


    override fun handleRequest(exchange: HttpServerExchange) {

        val query = exchange.queryParameters["query"]?.stringValue()
        val page = exchange.queryParameters["page"]?.intValue() ?: 0
        val pageSize = exchange.queryParameters["pageSize"]?.intValue() ?: 10

        if (query == null || query.isEmpty()) {
            exchange.statusCode = 400
            return
        }

        val elasticQuery = QueryBuilders.matchQuery("_all", query)
                .fuzziness(Fuzziness.fromEdits(2))

        val elasticResponse = elasticClient.prepareSearch("ethereum_tx", "ethereum_block",
                "bitcoin_tx", "bitcoin_block", "bitcoin_address")
                .setQuery(elasticQuery)
                .setFrom(page * pageSize).setSize(pageSize).setExplain(true)
                .execute()
                .actionGet()

        saveRequestProcessingStats(query, elasticResponse)

        val responseItems = elasticResponse.hits
                .map { hit -> ItemPreview(type = hit.index, data = hit.sourceAsString()) }

        val response = SearchResponse(
                query = query, page = page, pageSize = pageSize,
                items = responseItems, searchTime = elasticResponse.tookInMillis, totalHits = elasticResponse.hits.totalHits
        )
        val rawResponse = jsonSerializer.writeValueAsString(response)

        exchange.responseHeaders.put(Headers.CONTENT_TYPE, "application/json")
        exchange.responseSender.send(rawResponse)
    }


    private fun saveRequestProcessingStats(query: String, elasticResponse: org.elasticsearch.action.search.SearchResponse) {

        launch(AppContext.concurrentContext, CoroutineStart.DEFAULT) {

            val stats = SearchRequestProcessingStats(
                    total_hits = elasticResponse.hits.totalHits, max_score = elasticResponse.hits.maxScore,
                    search_time_ms = elasticResponse.tookInMillis, time = Instant.now().toString(),
                    time_m = Instant.now().epochSecond / 60, raw_request = query,
                    documents = elasticResponse.hits.map { hit -> DocumentKey(hit.index, hit.type, hit.id) }
            )

            kafkaProducer.send(SearchRequestProcessingStatsRecord(stats))
        }
    }
}
