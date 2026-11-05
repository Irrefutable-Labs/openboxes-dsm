// The demo's dashboard: only tiles whose figures come from the demo's own data and change as the story goes. Installed
// by openboxes.sh as ~/.grails/openboxes-config.groovy when that file does not exist; OpenBoxes reads it at start.
//
// Measured at Northgate after a full run: lot-and-bin count 2 (1 before the 500 boxes), inventory value $8,432 (up at
// the goods-received note, down when 120 ship, up $736 at the database edit and back at the correction), inventory
// summary 2 in stock, stock movements sent 1 to Lakeside.
//
// Left out, with why: "incoming" and "outgoing stock movements" count movements still in progress and the demo's
// shipment is sent in one step; "received by month" is zero at Northgate, which sends; "expiration summary" has no
// lot within its horizon (the demo's lots expire in 2028); receiving bin, putaways, delayed, discrepancies, fill rate,
// stock-outs, purchase orders and requests have nothing in this story.
openboxes.dashboardConfig.dashboards.mainDashboard.personal.widgets = [
        [widgetId: "inventoryByLotAndBin", order: 1],
        [widgetId: "inventoryValue", order: 2],
        [widgetId: "inventorySummary", order: 1],
        [widgetId: "sentStockMovements", order: 2],
]
