package com.lego.namnv.core.api;

import java.util.ArrayList;
import java.util.HashMap;

public class Demo {

    static class Node {
        public int id;
        public ArrayList<Integer> parentIds;
        public ArrayList<Node> children;

        public Node(int id, ArrayList<Integer> parentIds) {
            this.id = id;
            this.parentIds = parentIds;
            this.children = new ArrayList<Node>();
        }
    }

    public static void main(String[] args) {
        ArrayList<Node> list = new ArrayList<Node>();
        var a=new ArrayList<Integer>();
        a.add(2);
        a.add(1);
        list.add(new Node(4, a));

        list.add(new Node(1, new ArrayList<Integer>()));
        list.add(new Node(2, new ArrayList<Integer>() {{
            add(1);
        }}));
        list.add(new Node(3, new ArrayList<Integer>() {{
            add(1);
        }}));


        HashMap<Integer, Node> map = new HashMap<Integer, Node>();
        for (Node node : list) {
            map.put(node.id, node);
        }

        for (Node node : list) {
            for (int parentId : node.parentIds) {
                map.get(parentId).children.add(node);
            }
        }

        ArrayList<Node> sortedList = new ArrayList<Node>();
        for (Node node : list) {
            if (node.parentIds.isEmpty()) {
                addNodeToList(node, sortedList);
            }
        }

        for (Node node : sortedList) {
            System.out.println("id: " + node.id + ", parentIds: " + node.parentIds);
        }
    }

    public static void addNodeToList(Node node, ArrayList<Node> list) {
        list.add(node);
        for (Node child : node.children) {
            addNodeToList(child, list);
        }
    }
}