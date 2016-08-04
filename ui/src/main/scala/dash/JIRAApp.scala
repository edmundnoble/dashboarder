package dash

import com.thoughtworks.each.Monadic._
import dash.models.{ExpandableContentModel, TitledContentModel}
import monix.eval.Task
import monix.reactive.Observable
import monix.scalaz._
import org.scalajs.dom.XMLHttpRequest
import qq.QQCompiler.{CompiledFilter, OrCompilationError}
import qq._
import scodec.bits.BitVector
import upickle.{Js, json}

import scala.collection.immutable.IndexedSeq
import scala.concurrent.duration._
import scalaz.std.list._
import scalaz.syntax.traverse._

object JIRAApp {

  case class Filter(url: String, name: String, owner: String, jql: String, viewUrl: String)
  object Filter {
    implicit val pkl = SnakeOptionPickle.macroRW[Filter]
  }

  case class Issue(url: String, summary: String, key: String, project: String,
                   status: String, description: String) {
    def toTitledContentModel: TitledContentModel =
      TitledContentModel(title = s"$status - $key - $summary",
        titleUrl = Some("https://auviknetworks.atlassian.net/browse/" + key),
        content = description)
  }
  object Issue {
    implicit val pkl = SnakeOptionPickle.macroRW[Issue]
  }

  case class SearchResult(filter: Filter, issues: List[Issue]) {
    def toExpandableContentModel: ExpandableContentModel =
      ExpandableContentModel(title = filter.name, titleUrl = Some(filter.viewUrl), content = issues.map(_.toTitledContentModel))
  }
  object SearchResult {
    implicit val pkl = upickle.default.macroRW[SearchResult]
  }

  def fetchSearchResults: Task[Observable[IndexedSeq[ExpandableContentModel]]] = monadic[Task] {
    implicit val ajaxTimeout = Ajax.Timeout(4000.millis)

    val searchRequests = for {
      favoriteFilterResponse <- Observable.fromTask(
        Ajax.get(url = "https://jira.atlassian.net/rest/api/2/filter/favourite", headers = Creds.authData)
      )
      favoriteFilters: List[Filter] = json.read(favoriteFilterResponse.responseText).arr.map { r =>
        Filter(r.obj("self").str, r.obj("name").str, r.obj("owner").obj("name").str, r.obj("jql").str, r.obj("viewUrl").str)
      }(collection.breakOut)
      filterRequests <- (favoriteFilters: List[Filter]).traverse[Observable, XMLHttpRequest] { filter =>
        Observable.fromTask(
          Ajax.post(url = s"https://jira.atlassian.net/rest/api/2/search/",
            data = json.write(Js.Obj("jql" -> Js.Str(filter.jql), "maxResults" -> Js.Num(10))),
            headers = Creds.authData ++ Map("Content-Type" -> "application/json"))
        )
      }
    } yield (favoriteFilters, filterRequests)

    val qqProgram =
      """.issues.[] | {
        |  url: .self,
        |  summary: .fields.summary,
        |  key,
        |  project: .fields.project.name,
        |  description: (.fields.description | replaceAll("\n+\\s*"; " ↪ ")),
        |  status: .fields.status.name
        |}""".stripMargin

    val compiledQQProgram = StorageProgram.runProgram(Storage.local(TaskStorage(_)), getCompiledProgram(qqProgram)).each.valueOr(ex => throw ex)
    searchRequests.flatMap {
      case (filter, responses) =>
        responses.traverse[Observable, List[Issue]] { r =>
          val results = compiledQQProgram(upickle.json.read(r.responseText))
          Observable.fromTask(results map (_ flatMap (Issue.pkl.read.lift(_))))
        }.strengthL(filter)
    }.map { searchResults =>
      searchResults.zipped.map(SearchResult(_, _))(collection.breakOut).map(_.toExpandableContentModel)
    }
  }

  def getCompiledProgram(qqProgram: String): StorageProgram[OrCompilationError[CompiledFilter[Js.Value]]] = monadic[StorageProgram] {

    import qq.FilterProtocol._
    import StorageProgram._

    val hash = qqProgram.hashCode.toString

    val programInStorage = get(hash).each

    val decodedOptimizedProgram = programInStorage match {
      case None =>
        val parsedQQProgram = Parser.program.parse(qqProgram).get.value
        val optimizedProgram = Optimizer.optimize(parsedQQProgram)
        val encodedOptimizedProgram = programCodec.encode(optimizedProgram).require
        val out = encodedOptimizedProgram.toBase64
        update(hash, out).each
        optimizedProgram
      case Some(encodedProgram: String) =>
        programCodec.decode(BitVector.fromBase64(encodedProgram).get).require.value
    }

    QQCompiler.compileProgram(UpickleRuntime, decodedOptimizedProgram)
  }
}
