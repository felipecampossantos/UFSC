package br.ufsc.ine5410.trade;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static br.ufsc.ine5410.trade.Order.Type.*;

public class OrderBook implements AutoCloseable {
    private final @Nonnull String stockCode;
    private final @Nonnull TransactionProcessor transactionProcessor;
    private final @Nonnull PriorityQueue<Order> sellOrders, buyOrders;
    private boolean closed = false;

    public OrderBook(@Nonnull String stockCode,
                     @Nonnull TransactionProcessor transactionProcessor) {
        this.stockCode = stockCode;
        this.transactionProcessor = transactionProcessor;
        sellOrders = new PriorityQueue<>(100, new Comparator<Order>() {
            @Override
            public int compare(@Nonnull Order l, @Nonnull Order r) {
                return Double.compare(l.getPrice(), r.getPrice());
            }
        });
        buyOrders = new PriorityQueue<>(100, new Comparator<Order>() {
            @Override
            public int compare(@Nonnull Order l, @Nonnull Order r) {
                return Double.compare(r.getPrice(), l.getPrice());
            }
        });
    }

    public synchronized void post(@Nonnull Order order) {
        if (!order.getStock().equals(stockCode)) {
            String msg = toString() + " cannot process orders for " + order.getStock();
            throw new IllegalArgumentException(msg);
        }
        if (closed) {
            order.notifyCancellation();
            return;
        }
        (order.getType() == BUY ? buyOrders : sellOrders).add(order);
        order.notifyQueued();
        tryMatch();
    }

    private void tryMatch() {
        Order sell, buy;
        while ((sell = sellOrders.peek()) != null && (buy = buyOrders.peek()) != null) {
            if (sell.getPrice() <= buy.getPrice()) {
                Transaction trans = new Transaction(sell, buy);
                sell.notifyProcessing();
                buy.notifyProcessing();
                transactionProcessor.process(OrderBook.this, trans);
                Order removed = sellOrders.remove();
                assert removed == sell;
                removed = buyOrders.remove();
                assert removed == buy;
            } else {
                break;
            }
        }
    }

    @Override
    public String toString() {
        return String.format("OrderBook(%s)", stockCode);
    }

    @Override
    public void close()  {
        if (closed) return;
        closed = true;
        //any future post() call will be a no-op

        for (Order order : sellOrders) order.notifyCancellation();
        sellOrders.clear();
        for (Order order : buyOrders) order.notifyCancellation();
        buyOrders.clear();
    }
}
