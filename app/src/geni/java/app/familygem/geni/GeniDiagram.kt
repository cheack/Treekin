package app.familygem.geni

import android.content.Context
import android.graphics.Path
import app.familygem.util.SexUtil

/**
 * What the family tree diagram looks like in each appearance.
 * Original keeps upstream's vertical cards, borders and curved connectors; the Geni themes use horizontal photo cards
 * with a tinted background and straight connectors. Resource names with the geni_ prefix belong to the Geni cards only.
 */
object GeniDiagram {

    private val geni get() = GeniSettings.appearance(app.familygem.Global.context).isGeni

    /** Layout of the card of a person. */
    fun cardLayout(): Int = if (geni) app.familygem.R.layout.geni_diagram_card else app.familygem.R.layout.diagram_card

    /** Border of a card or minicard by sex. */
    fun border(sex: SexUtil): Int = when {
        sex.isMale() -> if (geni) app.familygem.R.drawable.geni_person_border_male else app.familygem.R.drawable.person_border_male
        sex.isFemale() -> if (geni) app.familygem.R.drawable.geni_person_border_female else app.familygem.R.drawable.person_border_female
        else -> if (geni) app.familygem.R.drawable.geni_person_border_undefined else app.familygem.R.drawable.person_border_undefined
    }

    /** In the Geni themes puts a silhouette in [view], the photo of a person without one; false in Original. */
    fun showPlaceholder(view: android.widget.ImageView, sex: SexUtil): Boolean {
        if (!geni) return false
        view.setImageResource(
            if (sex.isFemale()) app.familygem.R.drawable.geni_silhouette_female else app.familygem.R.drawable.geni_silhouette
        )
        return true
    }

    /** Background of the selected person. */
    fun selectedBackground(): Int =
        if (geni) app.familygem.R.drawable.geni_person_background_selected else app.familygem.R.drawable.person_background_selected

    /** Color of the connectors. */
    fun lineColor(context: Context, printing: Boolean): Int =
        if (printing) context.getColor(app.familygem.R.color.diagram_lines_print)
        else GeniAppearance.color(context, R.attr.geni_diagramLines)

    /** Colors the icons floating over the canvas to read on it (the canvas is dark in Original, light in Geni Light). */
    fun styleButtons(vararg buttons: android.widget.ImageButton) {
        for (button in buttons) {
            val color = GeniAppearance.color(button.context, R.attr.geni_diagramIcon)
            androidx.core.widget.ImageViewCompat.setImageTintList(button, android.content.res.ColorStateList.valueOf(color))
        }
    }

    /** Adds to [path], which is at (x1, y1), the connector to (x2, y2). */
    fun connect(path: Path, x1: Float, y1: Float, x2: Float, y2: Float, barOffset: Float) {
        if (geni) GeniGlue.connect(path, x1, y1, x2, y2, barOffset) else path.cubicTo(x1, y2, x2, y1, x2, y2)
    }
}
