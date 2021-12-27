/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package org.heigit.ors.routing.graphhopper.extensions.edgefilters.ch;

import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.storage.CHEdgeFilter;
import com.graphhopper.storage.RoutingCHEdgeIteratorState;
import com.graphhopper.storage.RoutingCHGraph;

public abstract class CHLevelEdgeFilter implements CHEdgeFilter {
    protected final FlagEncoder encoder;
    protected final RoutingCHGraph graph;
    protected final int maxNodes;
    protected int highestNode = -1;
    protected int highestNodeLevel = -1;
    protected int baseNode;
    protected int baseNodeLevel = -1;

    protected CHLevelEdgeFilter(RoutingCHGraph g, FlagEncoder encoder) {
        graph = g;
        maxNodes = g.getNodes();
        this.encoder = encoder;
    }

    @Override
    public boolean accept(RoutingCHEdgeIteratorState edgeIterState) {
        return false;
    }

    public int getHighestNode() {
        return highestNode;
    }

    public void setBaseNode(int nodeId) {
        baseNode = nodeId;
        if (nodeId < maxNodes)
            baseNodeLevel = graph.getLevel(nodeId);
    }

    public void updateHighestNode(RoutingCHEdgeIteratorState edgeIterState) {
        int adjNode = edgeIterState.getAdjNode();

        if (adjNode < maxNodes) {
            if (highestNode == -1 || highestNodeLevel < graph.getLevel(adjNode)) {
                highestNode = adjNode;
                highestNodeLevel = graph.getLevel(highestNode);
            }
        } else {
            if (highestNode == -1)
                highestNode = adjNode;
        }
    }

    protected boolean isAccessible(RoutingCHEdgeIteratorState edgeIterState, boolean reverse) {
        return edgeIterState.getWeight(reverse) != Double.POSITIVE_INFINITY;
    }
}
