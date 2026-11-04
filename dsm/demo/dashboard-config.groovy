// The demo's dashboard: the tiles that move with two products and one shipment. Installed by openboxes.sh as
// ~/.grails/openboxes-config.groovy when that file does not exist; OpenBoxes reads it at start.
//
// Left out on purpose: "incoming" and "outgoing stock movements" count movements still in progress (created, picking,
// pending receipt) at this warehouse, and the demo's shipment is created, picked and sent in one step and received
// minutes later, so those tiles stay at zero while stock plainly moves. "Sent" and "received stock movements" count
// what actually shipped and arrived, by month, and move with the story.
openboxes.dashboardConfig.dashboards.mainDashboard.personal.widgets = [
        [widgetId: "inventoryByLotAndBin", order: 1],
        [widgetId: "inventorySummary", order: 1],
        [widgetId: "sentStockMovements", order: 2],
        [widgetId: "receivedStockMovements", order: 3],
        [widgetId: "expirationSummary", order: 4],
]
