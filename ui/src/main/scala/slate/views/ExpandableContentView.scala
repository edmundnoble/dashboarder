package slate
package views

import japgolly.scalajs.react._
import japgolly.scalajs.react.component.ScalaBuilder
import japgolly.scalajs.react.extra.Reusability
import monix.execution.Scheduler
import slate.models.ExpandableContentModel

import scalacss.Defaults._

object ExpandableContentView {

  object Styles extends StyleSheet.Inline {

    import dsl._

    import scala.language.postfixOps

    val filterButtonIcon = style(
      addClassName("material-icons"),
      (transition := "transform 0.3s ease-out").important,
      marginTop(5 px)
    )

    val filterButtonExpanded = style(
      transform := "rotateX(-180deg)",
      perspective(600 pt)
    )

    val base = style(
      width(100 %%),
      addClassNames("mdl-color--grey-100", "mdl-color-text--grey-600"),
      overflow.hidden,
      marginRight(20 px),
      marginBottom(20 px),
      marginTop(5 px)
    )

    val header = style(
      width(100 %%),
      addClassName("mdl-card__title-text"),
      borderBottom.rgba(0, 0, 0, .13),
      borderBottomStyle.solid,
      borderBottomWidth(1 px),
      display.inlineBlock,
      marginTop(-10 px)
    )

    val expandToggleButton = style(
      addClassNames("mdl-button", "mdl-js-button", "mdl-js-ripple-effect"),
      minWidth(56 px).important,
      marginRight(10 px),
      marginTop(5 px),
      float.right,
      lineHeight.`0`.important
    )

    val headerLeft = style(
      marginLeft(10 px),
      float left,
      marginTop(10 px),
      marginBottom(10 px)
    )

    val number = style(
      fontSize(22 px),
      fontWeight._600,
      addClassName("mdl-color-text--grey-800"),
      fontFamily :=! "Akrobat",
      marginLeft(5 px),
      paddingRight(5 px),
      display inline
    )

    val title = style(
      fontSize(22 px),
      fontWeight._300,
      addClassName("mdl-color-text--grey-500"),
      fontFamily :=! "Akrobat",
      display inline
    )

    val content = style(
      addClassName("mdl-list"),
      paddingTop(5 px).important,
      marginLeft(10 px),
      paddingRight(10 px)
    )

    val animationGroup = new slate.views.ScrollFadeContainer("expandableContentView")

  }

  final case class ExpandableState(expanded: Boolean) {
    final def toggleExpanded = {
      copy(expanded = !expanded)
    }
  }

  object ExpandableState {
    implicit val reusability: Reusability[ExpandableState] =
      Reusability.by(_.expanded)
  }

  final case class ExpandableContentProps(model: ExpandableContentModel, initiallyExpanded: Boolean)

  object ExpandableContentProps {
    implicit val reusability: Reusability[ExpandableContentProps] =
      Reusability.caseClass[ExpandableContentProps]
  }

  def builder(implicit sch: Scheduler
             ): ScalaBuilder.Step4[ExpandableContentProps, Children.None, ExpandableState, Unit] = {
    import japgolly.scalajs.react.vdom.all._

    import scalacss.ScalaCssReact._

    def buttonStyleForState(state: ExpandableState): TagMod = {
      val nodes: Seq[TagMod] =
        if (state.expanded) Seq(Styles.filterButtonExpanded: TagMod)
        else Seq.empty
      TagMod(nodes :+ (Styles.filterButtonIcon: TagMod) :+ ("expand_more": TagMod): _*)
    }

    ScalaComponent.builder[ExpandableContentProps]("Expandable content view")
      .initialState_P[ExpandableState](props => ExpandableState(expanded = props.initiallyExpanded))
      .renderPS { ($, props, state) =>
        val titleLink = href :=? props.model.titleUrl
        div(key := props.model.title, Styles.base,
          div(Styles.header,
            div(Styles.headerLeft,
              span(Styles.number,
                props.model.content.length.toString()),
              a(Styles.title, props.model.title, titleLink)
            ),
            button(Styles.expandToggleButton,
              onClick --> $.modState(_.toggleExpanded),
              i(buttonStyleForState(state))
            )
          ),
          span(
            (Styles.content: TagMod) ::
              (if (state.expanded) props.model.content.map(TitledContentView.builder.build.apply(_): VdomElement)
              else Nil): _*
          )
        )
      }
      .configure(Reusability.shouldComponentUpdate)
  }

}
