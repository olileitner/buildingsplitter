package org.openstreetmap.josm.plugins.buildingsplitter;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;

final class VisibleAddressContextService {

    List<String> collectVisibleStreetNames(DataSet dataSet) {
        if (dataSet == null) {
            return List.of();
        }

        Set<String> names = new TreeSet<>(Collator.getInstance());
        for (Way way : getWaysFromCurrentView(dataSet)) {
            if (!way.isUsable() || !way.hasTag("highway")) {
                continue;
            }
            String name = normalizeValue(way.get("name"));
            if (!name.isEmpty()) {
                names.add(name);
            }
        }

        return new ArrayList<>(names);
    }

    String detectUniformVisiblePostcode(DataSet dataSet) {
        if (dataSet == null) {
            return "";
        }

        Set<String> postcodes = new HashSet<>();
        for (Way way : getWaysFromCurrentView(dataSet)) {
            if (!way.isUsable() || !way.hasTag("building")) {
                continue;
            }
            addPostcode(postcodes, way.get("addr:postcode"));
            if (postcodes.size() > 1) {
                return "";
            }
        }

        for (Relation relation : getRelationsFromCurrentView(dataSet)) {
            if (!relation.isUsable() || !relation.hasTag("building")) {
                continue;
            }
            addPostcode(postcodes, relation.get("addr:postcode"));
            if (postcodes.size() > 1) {
                return "";
            }
        }

        return postcodes.size() == 1 ? postcodes.iterator().next() : "";
    }

    private Collection<Way> getWaysFromCurrentView(DataSet dataSet) {
        Bounds bounds = getCurrentViewBounds();
        return bounds == null ? dataSet.getWays() : dataSet.searchWays(bounds.toBBox());
    }

    private Collection<Relation> getRelationsFromCurrentView(DataSet dataSet) {
        Bounds bounds = getCurrentViewBounds();
        return bounds == null ? dataSet.getRelations() : dataSet.searchRelations(bounds.toBBox());
    }

    private Bounds getCurrentViewBounds() {
        MapFrame map = MainApplication.getMap();
        return map != null && map.mapView != null ? map.mapView.getRealBounds() : null;
    }

    private void addPostcode(Set<String> postcodes, String postcode) {
        String normalized = normalizeValue(postcode);
        if (!normalized.isEmpty()) {
            postcodes.add(normalized);
        }
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }
}

