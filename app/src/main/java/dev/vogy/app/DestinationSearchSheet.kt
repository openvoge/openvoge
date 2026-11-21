package dev.vogy.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Where to?" sheet: search places as you type (Photon) or jump to the nearest fuel stations
 * (Overpass). Picking a result hands the [Place] to [onPicked] and closes the sheet.
 */
class DestinationSearchSheet(
    private val activity: AppCompatActivity,
    /** Applies the app's dotted neo-brutalist sheet background. */
    private val decorate: (View) -> Unit,
    /** The rider's position, if known: biases search results and powers "nearest fuel". */
    private val origin: () -> GeoPoint?,
    private val distanceUnit: () -> SpeedUnit,
    private val onPicked: (Place) -> Unit,
) {

    private companion object {
        const val TYPING_PAUSE_MS = 450L
        const val MIN_QUERY_LENGTH = 3
    }

    private var job: Job? = null

    @SuppressLint("InflateParams")
    fun show() {
        val dialog = BottomSheetDialog(activity, R.style.Theme_OpenVoge_BottomSheetDialog)
        val content = activity.layoutInflater.inflate(R.layout.bottom_sheet_destination_search, null)
        decorate(content)
        val input = content.findViewById<EditText>(R.id.etDestinationQuery)
        val status = content.findViewById<TextView>(R.id.tvSearchStatus)
        val resultsGroup = content.findViewById<View>(R.id.groupPlaceResults)
        val resultsList = content.findViewById<LinearLayout>(R.id.layoutPlaceResults)
        val density = activity.resources.displayMetrics.density

        fun showStatus(message: String?) {
            status.text = message
            status.visibility = if (message == null) View.GONE else View.VISIBLE
        }

        fun render(places: List<Place>) {
            resultsList.removeAllViews()
            val near = origin()
            places.forEachIndexed { index, place ->
                if (index > 0) {
                    resultsList.addView(View(activity).apply {
                        setBackgroundColor(Color.BLACK)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (3 * density).toInt())
                    })
                }
                val row = activity.layoutInflater.inflate(R.layout.item_place_row, resultsList, false)
                row.findViewById<ImageView>(R.id.ivPlaceIcon)
                    .setImageResource(if (place.isFuel) R.drawable.ic_fuel_neo else R.drawable.ic_pin_neo)
                row.findViewById<TextView>(R.id.tvPlaceName).text = place.name
                val distance = near?.let {
                    RouteFormat.distance(it.distanceTo(place.point), distanceUnit(), activity.resources.configuration.locales[0])
                }
                row.findViewById<TextView>(R.id.tvPlaceDetail).text =
                    listOfNotNull(distance, place.detail.ifBlank { null }).joinToString(" · ")
                row.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dialog.dismiss()
                    onPicked(place)
                }
                resultsList.addView(row)
            }
            resultsGroup.visibility = if (places.isEmpty()) View.GONE else View.VISIBLE
        }

        /** Runs one lookup at a time: a newer one (or closing the sheet) cancels the previous. */
        fun lookUp(pauseMs: Long, emptyMessage: String, fetch: suspend () -> List<Place>) {
            job?.cancel()
            job = activity.lifecycleScope.launch {
                delay(pauseMs)
                showStatus(activity.getString(R.string.map_search_searching))
                try {
                    val places = fetch()
                    render(places)
                    showStatus(if (places.isEmpty()) emptyMessage else null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    render(emptyList())
                    showStatus(
                        activity.getString(
                            if (e is OpenMapServices.ServiceException) R.string.map_service_unavailable
                            else R.string.map_search_failed
                        )
                    )
                }
            }
        }

        fun search(pauseMs: Long) {
            val query = input.text.toString().trim()
            if (query.length < MIN_QUERY_LENGTH) {
                job?.cancel()
                showStatus(null)
                return
            }
            lookUp(pauseMs, activity.getString(R.string.map_search_empty)) {
                OpenMapServices.searchPlaces(query, origin())
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = search(TYPING_PAUSE_MS)
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(pauseMs = 0)
                true
            } else {
                false
            }
        }
        content.findViewById<View>(R.id.btnNearestFuel).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val near = origin()
            if (near == null) {
                showStatus(activity.getString(R.string.map_search_need_location))
                return@setOnClickListener
            }
            val fallbackName = activity.getString(R.string.map_fuel_fallback)
            val language = if (activity.resources.configuration.locales[0].language == "es") "es" else "en"
            lookUp(pauseMs = 0, activity.getString(R.string.map_fuel_none)) {
                OpenMapServices.nearestFuel(near, fallbackName, language)
            }
        }
        content.findViewById<View>(R.id.btnSearchClose).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { job?.cancel() }

        // Keep the field and results above the keyboard and the navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom
            view.updatePadding(bottom = (24 * density).toInt() + bottom)
            insets
        }
        dialog.setContentView(content)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = null
                ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
                    v.updatePadding(bottom = 0)
                    insets
                }
            }
            input.requestFocus()
        }
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }
}
