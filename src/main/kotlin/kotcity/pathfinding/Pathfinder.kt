package kotcity.pathfinding

import aballano.kotlinmemoization.memoize
import kotcity.data.*
import kotcity.util.pmap
import kotlinx.coroutines.experimental.runBlocking

const val MAX_DISTANCE = 50f

enum class Direction {
    NORTH, SOUTH, EAST, WEST, STATIONARY
}

enum class TransitType {
    ROAD
}

data class Path (
        val nodes: List<NavigationNode> = emptyList()
) {
    fun distance(): Int {
        return nodes.count()
    }
}

data class NavigationNode(
        val cityMap: CityMap,
        val coordinate: BlockCoordinate,
        val parent: NavigationNode?,
        val score: Double,
        val transitType: TransitType = TransitType.ROAD,
        val direction: Direction
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) return false
        val that = other as NavigationNode
        return this.coordinate == that.coordinate
    }

    override fun hashCode(): Int {
        return this.coordinate.hashCode()
    }
}

object Pathfinder {

    private fun findNearestTrade(cityMap: CityMap, start: List<BlockCoordinate>, quantity: Int, buildingFilter: (Building, Int) -> Boolean): List<BlockCoordinate> {
        return start.flatMap { coordinate ->
            val buildings = cityMap.nearestBuildings(coordinate, MAX_DISTANCE)

            // OK now we want only ones with labor...
            buildings.filter {
                val building = it.second
                buildingFilter(building, quantity)
            }
        }.flatMap { blocksFor(it.second, it.first) }
    }

    fun pathToNearestTrade(cityMap: CityMap, start: List<BlockCoordinate>, quantity: Int, buildingFilter: (Building, Int) -> Boolean): Path? {
        val nearest = findNearestTrade(cityMap, start, quantity, buildingFilter)
        return tripTo(cityMap, start, nearest)
    }

    private fun blocksFor(building: Building, coordinate: BlockCoordinate): List<BlockCoordinate> {
        val xRange = coordinate.x .. (coordinate.x + building.width - 1)
        val yRange = coordinate.y .. (coordinate.y + building.height - 1)
        return xRange.flatMap { x->
            yRange.map { y->
                BlockCoordinate(x, y)
            }
        }
    }

    fun pathToNearestLabor(cityMap: CityMap, start: List<BlockCoordinate>, quantity: Int = 1): Path? {
        val nearest = findNearestTrade(cityMap, start, quantity) {
            building, quantity -> building.sellingQuantity(Tradeable.LABOR) >= quantity
        }
        return tripTo(cityMap, start, nearest)
    }

    fun pathToNearestJob(cityMap: CityMap, start: List<BlockCoordinate>, quantity: Int = 1): Path? {
        val nearest = findNearestTrade(cityMap, start, quantity) {
            building, quantity -> building.buyingQuantity(Tradeable.LABOR) >= quantity
        }
        return tripTo(cityMap, start, nearest)
    }

    private fun heuristic(start: BlockCoordinate, destinations: List<BlockCoordinate>): Double {
        // calculate manhattan distance to each...
        return runBlocking {
            val scores = destinations.pmap(this.coroutineContext) {
                manhattanDistance(start, it)
            }
            scores.min() ?: 0.0
        }

    }

    private val memoizedHeuristic = ::heuristic.memoize(256)

    private fun manhattanDistance(start: BlockCoordinate, destination: BlockCoordinate): Double {
        return Math.abs(start.x-destination.x) + Math.abs(start.y-destination.y).toDouble()
    }

    private fun drivable(cityMap: CityMap, node: NavigationNode): Boolean {
        // make sure we got a road under it...
        return cityMap.buildingLayer[node.coordinate]?.type == BuildingType.ROAD
    }

    fun tripTo(
            cityMap: CityMap,
            source: List<BlockCoordinate>,
            destinations: List<BlockCoordinate>
    ): Path? {

        // that first time we can go up to 3 hops away and still work right...
        val paddedSources = mutableListOf(*source.toTypedArray())
        paddedSources.addAll(source.toTypedArray().flatMap { it.neighbors(3) }.distinct() )

        // switch these to list of navigation nodes...
        val openList = mutableSetOf(*(
                paddedSources.map { NavigationNode(
                        cityMap,
                        it,
                        null,
                        memoizedHeuristic(it, destinations),
                        TransitType.ROAD,
                        Direction.STATIONARY
                )}.toTypedArray())
        )
        val closedList = mutableSetOf<NavigationNode>()

        var done = false

        var lastNode: NavigationNode? = null

        while (!done) {
            // bail out if we have no nodes left in the open list
            val activeNode = openList.minBy { it.score } ?: return null
            // now remove it from open list...
            openList.remove(activeNode)
            closedList.add(activeNode)

            if (destinations.contains(activeNode.coordinate)) {
                done = true
                lastNode = activeNode
            }

            // TODO: maybe pull out into lambda so we can re-use pathfinder...
            fun maybeAppendNode(node: NavigationNode) {
                if (!closedList.contains(node) && !openList.contains(node)) {
                    if (drivable(cityMap, node) || destinations.contains(node.coordinate)) {
                        openList.add(node)
                    } else {
                        closedList.add(node)
                    }
                }
            }

            // ok figure out the dang neighbors...
            val north = BlockCoordinate(activeNode.coordinate.x, activeNode.coordinate.y-1)
            val northNode = NavigationNode(cityMap, north, activeNode, memoizedHeuristic(north, destinations), TransitType.ROAD, Direction.NORTH)
            maybeAppendNode(northNode)

            val south = BlockCoordinate(activeNode.coordinate.x, activeNode.coordinate.y+1)
            val southNode = NavigationNode(cityMap, south, activeNode, memoizedHeuristic(south, destinations), TransitType.ROAD, Direction.SOUTH)
            maybeAppendNode(southNode)

            val east = BlockCoordinate(activeNode.coordinate.x+1, activeNode.coordinate.y)
            val eastNode = NavigationNode(cityMap, east, activeNode, memoizedHeuristic(east, destinations), TransitType.ROAD, Direction.EAST)
            maybeAppendNode(eastNode)

            val west = BlockCoordinate(activeNode.coordinate.x-1, activeNode.coordinate.y)
            val westNode = NavigationNode(cityMap, west, activeNode, memoizedHeuristic(west, destinations), TransitType.ROAD, Direction.WEST)
            maybeAppendNode(westNode)

        }

        return lastNode?.let {
            // now we can just recurse back from
            val pathNodes = mutableListOf<NavigationNode>()
            var activeNode = lastNode

            while (activeNode != null) {
                activeNode.let {
                    pathNodes.add(it)
                }
                activeNode = activeNode.parent
            }

            Path(pathNodes)
        } ?: null

    }


}