// The demo's dashboard: only the tiles that have something to show for two products and one shipment. Installed by
// openboxes.sh as ~/.grails/openboxes-config.groovy when that file does not exist; OpenBoxes reads it at start.
openboxes.dashboardConfig.dashboards.mainDashboard.personal.widgets = [
        [widgetId: "inventoryByLotAndBin", order: 1],
        [widgetId: "inProgressShipments", order: 2],
        [widgetId: "inventorySummary", order: 1],
        [widgetId: "incomingStock", order: 2],
        [widgetId: "outgoingStock", order: 3],
        [widgetId: "expirationSummary", order: 4],
]
