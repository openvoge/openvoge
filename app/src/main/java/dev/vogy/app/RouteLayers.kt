package dev.vogy.app

import android.graphics.PointF
import android.graphics.RectF
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Draws planned routes and the destination on the map, in the same hard-edged style as the base map:
 * the chosen route in the accent color with a thick black casing, alternatives as a faded version
 * of it. The base map keeps the accent off its roads, so a route never blends into a highway.
 */
class RouteLayers(
    private val style: Style,
    accentColor: Int,
) {

    private companion object {
        const val SOURCE_ROUTES = "openvoge-routes"
        const val SOURCE_DESTINATION = "openvoge-destination"
        const val LAYER_ALT_CASING = "openvoge-route-alt-casing"
        const val LAYER_ALT_LINE = "openvoge-route-alt"
        const val LAYER_MAIN_CASING = "openvoge-route-casing"
        const val LAYER_MAIN_LINE = "openvoge-route"
        const val LAYER_DESTINATION_SHADOW = "openvoge-destination-shadow"
        const val LAYER_DESTINATION = "openvoge-destination"
        const val LAYER_DESTINATION_DOT = "openvoge-destination-dot"
        const val PROP_ROUTE = "route"
        const val PROP_SELECTED = "selected"
        /** Street names stay readable on top of the route line. */
        const val BELOW_LAYER = "road_label"
    }

    private val routesSource = GeoJsonSource(SOURCE_ROUTES)
    private val destinationSource = GeoJsonSource(SOURCE_DESTINATION)

    init {
        style.addSource(routesSource)
        style.addSource(destinationSource)

        val selected = Expression.eq(Expression.get(PROP_SELECTED), true)
        val alternative = Expression.eq(Expression.get(PROP_SELECTED), false)
        addBelowLabels(
            routeLine(LAYER_ALT_CASING, 0xFF000000.toInt(), widths = floatArrayOf(6f, 9f, 15f))
                .withProperties(PropertyFactory.lineOpacity(0.55f))
                .withFilter(alternative)
        )
        addBelowLabels(
            routeLine(LAYER_ALT_LINE, accentColor, widths = floatArrayOf(3f, 5f, 10f))
                .withProperties(PropertyFactory.lineOpacity(0.5f))
                .withFilter(alternative)
        )
        addBelowLabels(routeLine(LAYER_MAIN_CASING, 0xFF000000.toInt(), widths = floatArrayOf(8f, 12f, 19f)).withFilter(selected))
        addBelowLabels(routeLine(LAYER_MAIN_LINE, accentColor, widths = floatArrayOf(4.5f, 7f, 13f)).withFilter(selected))

        // Destination: black-and-white target with a hard offset shadow, on top of everything
        style.addLayer(
            CircleLayer(LAYER_DESTINATION_SHADOW, SOURCE_DESTINATION).withProperties(
                PropertyFactory.circleRadius(12f),
                PropertyFactory.circleColor(0xFF000000.toInt()),
                PropertyFactory.circleTranslate(arrayOf(3f, 3f)),
            )
        )
        style.addLayer(
            CircleLayer(LAYER_DESTINATION, SOURCE_DESTINATION).withProperties(
                PropertyFactory.circleRadius(11f),
                PropertyFactory.circleColor(0xFFFFFFFF.toInt()),
                PropertyFactory.circleStrokeColor(0xFF000000.toInt()),
                PropertyFactory.circleStrokeWidth(4f),
            )
        )
        style.addLayer(
            CircleLayer(LAYER_DESTINATION_DOT, SOURCE_DESTINATION).withProperties(
                PropertyFactory.circleRadius(4f),
                PropertyFactory.circleColor(0xFF000000.toInt()),
            )
        )
    }

    /** Shows [routes]; [selectedIndex] is drawn in the accent color, the rest as alternatives. */
    fun showRoutes(routes: List<MotoRoute>, selectedIndex: Int) {
        val features = routes.mapIndexed { index, route ->
            Feature.fromGeometry(LineString.fromLngLats(route.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addNumberProperty(PROP_ROUTE, index)
                addBooleanProperty(PROP_SELECTED, index == selectedIndex)
            }
        }
        // Alternatives first so the selected route is drawn on top where they overlap
        routesSource.setGeoJson(FeatureCollection.fromFeatures(features.sortedBy { it.getBooleanProperty(PROP_SELECTED) }))
    }

    fun showDestination(point: GeoPoint?) {
        destinationSource.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(point?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })
            )
        )
    }

    fun clear() {
        routesSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        showDestination(null)
    }

    /** Index of the route line under [latLng] (within [touchRadiusPx]), or null. */
    fun routeIndexAt(map: MapLibreMap, latLng: LatLng, touchRadiusPx: Float): Int? {
        val screen: PointF = map.projection.toScreenLocation(latLng)
        val area = RectF(
            screen.x - touchRadiusPx, screen.y - touchRadiusPx,
            screen.x + touchRadiusPx, screen.y + touchRadiusPx
        )
        return map.queryRenderedFeatures(area, LAYER_ALT_LINE, LAYER_MAIN_LINE)
            .firstOrNull { it.hasProperty(PROP_ROUTE) }
            ?.getNumberProperty(PROP_ROUTE)
            ?.toInt()
    }

    private fun routeLine(id: String, color: Int, widths: FloatArray): LineLayer =
        LineLayer(id, SOURCE_ROUTES).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(8, widths[0]),
                    Expression.stop(14, widths[1]),
                    Expression.stop(18, widths[2]),
                )
            ),
        )

    private fun addBelowLabels(layer: Layer) {
        if (style.getLayer(BELOW_LAYER) != null) style.addLayerBelow(layer, BELOW_LAYER) else style.addLayer(layer)
    }
}
