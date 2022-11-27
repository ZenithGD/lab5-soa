package soa.camel

import com.google.gson.Gson
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.apache.camel.ProducerTemplate
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.model.dataformat.JsonLibrary
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody


@SpringBootApplication
class Application

fun main(vararg args: String) {
    runApplication<Application>(*args)
}

const val DIRECT_ROUTE = "direct:twitter"
const val COUNT_ROUTE = "direct:extractor"
const val LOG_ROUTE = "direct:log"
const val DB_ROUTE = "direct:db"
const val INDEX_VIEW = "index"

const val PREFIX_LENGTH = 4
const val DEFAULT_MSG_LIMIT = 5

@Controller
class SearchController(private val producerTemplate: ProducerTemplate) {
    @RequestMapping("/")
    fun index() = INDEX_VIEW

    @RequestMapping(value = ["/search"])
    @ResponseBody
    fun search(@RequestParam("q") q: String?): Any =
        producerTemplate.requestBodyAndHeader(DIRECT_ROUTE, "mandalorian", "keywords", q)
}

@Component
class Router(meterRegistry: MeterRegistry) : RouteBuilder() {

    private val perKeywordMessages = TaggedCounter("per-keyword-messages", "keyword", meterRegistry)

    override fun configure() {
        from(DIRECT_ROUTE)
            .process { exchange ->
                val originalKeywords = exchange.getIn().getHeader("keywords") as? String ?: ""
                val (maxList, keywordsList) = originalKeywords.split(" ")
                    .partition { it.startsWith("max:") }
                exchange.getIn().setHeader("keywords", keywordsList.joinToString(" "))

                val max = maxList.firstOrNull()?.drop(PREFIX_LENGTH)?.toIntOrNull() ?: DEFAULT_MSG_LIMIT
                exchange.getIn().setHeader("count", max)
            }
            .toD("twitter-search:\${header.keywords}?count=\${header.count}")
            .wireTap(LOG_ROUTE)
            .wireTap(COUNT_ROUTE)
            .wireTap(DB_ROUTE)

        from(LOG_ROUTE)
            .marshal().json(JsonLibrary.Gson)
            .to("file://log?fileName=\${date:now:yyyy/MM/dd/HH-mm-ss.SSS}.json")

        from(COUNT_ROUTE)
            .split(body())
            .process { exchange ->
                val keyword = exchange.getIn().getHeader("keywords") as? String
                keyword?.split(" ")?.map { perKeywordMessages.increment(it) }
            }

        from(DB_ROUTE)
            .split(body())
            .process { exchange ->
                val gson = Gson()
                val jsonStr = gson.toJson(exchange.getIn().body)

                exchange.getIn().setHeader("hashCode", jsonStr.hashCode())
                exchange.getIn().body = jsonStr
            }
            .setBody(simple("insert into tweetdata values('\${date:now:yyyy/MM/dd/HH-mm-ss.SSS}', '\${body}')"))
            .to("jdbc:dataSource");

        // Note: I had to manually generate the id, because it seems that H2 doesn't auto increment the
        // generated id. Choosing another DB engine might solve this issue.
    }
}

class TaggedCounter(private val name: String, private val tagName: String, private val registry: MeterRegistry) {
    private val counters: MutableMap<String, Counter> = HashMap()
    fun increment(tagValue: String) {
        counters.getOrPut(tagValue) {
            Counter.builder(name).tags(tagName, tagValue).register(registry)
        }.increment()
    }
}
