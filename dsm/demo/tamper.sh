#!/bin/bash
# The scene's attack: someone with database access adds 40 boxes of amoxicillin to Northgate's books, by hand.
sql() { docker exec openboxes-demo-a mysql -uopenboxes -popenboxes openboxes -Nse "$1" 2>&1 | grep -v Warning; }
P=$(sql "select id from product where product_code='AMX500'")
E=$(sql "select e.id from transaction_entry e join inventory_item i on i.id=e.inventory_item_id join transaction t on t.id=e.transaction_id where i.product_id='$P' and e.quantity=500 limit 1")
[ -n "$E" ] || { echo "no 500 entry to edit"; exit 1; }
sql "update transaction_entry set quantity=quantity+40 where id='$E'; update product_availability set quantity_on_hand=quantity_on_hand+40 where product_id='$P' and quantity_on_hand>0"
echo "edited entry; books now say:"; sql "select sum(quantity_on_hand) from product_availability where product_id='$P'"
