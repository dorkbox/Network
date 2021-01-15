@file:Suppress("unused")

package dorkbox.network.other

class Node<T>(val value: T) {
    var prev: Node<T>? = null
    var next: Node<T>? = null
}

class LinkedList<T> : List<T> {
    override val size: Int
        get() = sizeOfList

    private var sizeOfList: Int = 0

    private var head: Node<T>? = null
    private var tail: Node<T>? = null

    fun nodeAtIndex(index: Int): Node<T> {
        checkPositionIndex(index)

        if (index >= 0) {
            var node = head
            var i = index
            while (node != null) {
                if (i == 0) return node
                i -= 1
                node = node.next
            }
        }

        throw IndexOutOfBoundsException("Index was invalid or did not have any value assigned")
    }

    override fun get(index: Int): T {
        return nodeAtIndex(index).value
    }

    /**
     * Add new item to the end of the list
     *
     * @param item - item we are adding to the end of the list
     */
    fun add(item: T): Node<T> {
        val newNode = Node(item)
        addToTail(newNode)
        return newNode
    }

    /**
     * Add new item to the end of the list
     *
     * @param item - item we are adding to the end of the list
     */
    fun addToTail(item: T): Node<T> {
        val newNode = Node(item)
        addToTail(newNode)
        return newNode
    }

    /**
     * add new node to head of the list
     *
     * @param newNode - Data we are adding to the beginning of the list
     */
    fun addToHead(newNode: Node<T>) {
        val firstNode = head

        if (firstNode != null) {
            newNode.next = firstNode
            firstNode.prev = newNode

            head = newNode
        } else {
            head = newNode
            tail = newNode
        }

        sizeOfList++
    }

    /**
     * Add new node to end of the list
     *
     * @param newNode - node we are adding to the end of the list
     */
    fun addToTail(newNode: Node<T>) {
        val lastNode = tail

        if (lastNode != null) {
            newNode.prev = lastNode
            lastNode.next = newNode

            tail = newNode
        } else {
            head = newNode
            tail = newNode
        }

        sizeOfList++
    }


    /**
     * Searches, and removes and item from the list
     *
     * @param item - the item we are removing from the list
     */
    fun remove(item: T) : Int {
        var node = head

        while (node != null) {
            if (node.value != item) {
                node = node.next
            } else {
                return removeNode(node)
            }
        }

        // nothing was removed
        return sizeOfList
    }

    /**
     * Remove a node from the list
     *
     * @param node - Node we are removing
     *
     * @return - size of list after removal
     */
    fun removeNode(node: Node<T>): Int {
        val prev = node.prev
        val next = node.next

        if (prev != null && next != null) {
            // We are in the middle. Link the previous to the next and vice versa
            prev.next = next
            next.prev = prev
        } else if (prev == null && next != null) {
            // We are the head. Unlink and make the next the new head
            next.prev = null

            head = next
        } else if (prev != null && next == null) {
            // We are the tail. Unlink and make the prev the new tail
            prev.next = null

            tail = prev
        } else if (prev == null && next == null) {
            // We are the only node in the list
            head = null
            tail = null
        }

        node.prev = null
        node.next = null
        sizeOfList--

        return sizeOfList
    }


    /**
     * Clears all of the contents of this list
     */
    fun clear() {
        removeAll()
    }

    /**
     * Remove everything from the list
     */
    fun removeAll() {
        head = null
        tail = null
    }

    /**
     * Move node to front of the list
     *
     * @param node - Node we are moving
     */
    fun moveNodeToFront(node: Node<T>) {
        val prev = node.prev
        val next = node.next

        if (prev == null && next != null) {
            // We are the head. Do nothing
            return
        } else if (prev == null && next == null) {
            // We are the head. Do nothing
            return
        }

        if (prev != null && next != null) {
            // We are in the middle. Link the previous to the next and vice versa
            prev.next = next
            next.prev = prev
        } else if (prev != null && next == null) {
            // We are the tail. Unlink and make the prev the new tail
            prev.next = null

            tail = prev
        }
        node.prev = null
        node.next = head

        head = node
    }

    /**
     * Add node to front of list or moves to front of list if it is in the list
     *
     * @param node - Node we are adding or moving
     *
     * @return Size of list
     */
    fun addOrMoveNodeToFront(node: Node<T>): Int {
        val prev = node.prev
        val next = node.next

        if (prev == null && next == null) {
            if (node == head) {
                //We are the head. Do Nothing
                return sizeOfList
            }
            // We are not linked to anything, so need to be added to the head of the list.
            addToHead(node)
            return sizeOfList
        }

        if (prev == null && next != null) {
            // We are the head. Do nothing
            return sizeOfList
        }

        if (prev != null && next == null) {
            // We are the tail. Unlink and make the prev the new tail
            prev.next = null

            tail = prev
        } else if (prev != null && next != null) {
            // We are in the middle. Link the previous to the next and vice versa
            prev.next = next
            next.prev = prev
        }

        head!!.prev = node
        node.next = head
        node.prev = null

        head = node

        return sizeOfList
    }

    fun forEach(function: (T) -> Unit) {
        var node = head

        while (node != null) {
            function(node.value)
            node = node.next
        }
    }

    private fun isPositionIndex(index: Int): Boolean {
        return index in 0..sizeOfList
    }

    private fun outOfBoundsMsg(index: Int): String {
        return "Index: $index, Size: $sizeOfList"
    }

    private fun checkPositionIndex(index: Int) {
        if (!isPositionIndex(index))
            throw IndexOutOfBoundsException(outOfBoundsMsg(index))
    }

    fun reverseIterator(): ListIterator<T> {
        return listIterator(sizeOfList)
    }

    override operator fun iterator(): ListIterator<T> {
        return listIterator(0)
    }

    override fun listIterator(): ListIterator<T> {
        return listIterator(0)
    }

    override fun listIterator(index: Int): ListIterator<T> {
        checkPositionIndex(index)
        return ListItr(index)
    }

    private inner class ListItr internal constructor(private var nextIndex: Int) : ListIterator<T> {
        private var lastReturned: Node<T>? = null
        private var next: Node<T>? = null

        init {
            next = if (nextIndex == sizeOfList) null else nodeAtIndex(nextIndex)
        }

        override fun hasNext(): Boolean {
            return nextIndex < sizeOfList
        }

        override fun next(): T {
            if (!hasNext())
                throw NoSuchElementException()

            lastReturned = next
            next = next!!.next
            nextIndex++
            return lastReturned!!.value
        }

        override fun hasPrevious(): Boolean {
            return nextIndex > 0
        }

        override fun previous(): T {
            if (!hasPrevious()) {
                throw NoSuchElementException()
            }

            next = if (next == null) tail else next!!.prev
            lastReturned = next
            nextIndex--
            return lastReturned!!.value
        }

        override fun nextIndex(): Int {
            return nextIndex
        }

        override fun previousIndex(): Int {
            return nextIndex - 1
        }
    }

    override fun toString(): String {
        var s = "["
        var node = head
        while (node != null) {
            s += "${node.value}"
            node = node.next
            if (node != null) {
                s += ", "
            }
        }
        return "$s]"
    }

    override fun contains(element: T): Boolean {
        var node = head

        while (node != null) {
            if (node.value != element) {
                node = node.next
            } else {
                return true
            }
        }

        // nothing was found
        return false
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        var foundAllItems: Boolean
        elements.forEach {
            foundAllItems = contains(it)
            if (!foundAllItems) {
                return false
            }
        }

        return true
    }

    override fun isEmpty(): Boolean {
        return head == null
    }

    /**
     * Returns the index of the first occurrence of the specified element in the list, or -1 if the specified
     * element is not contained in the list.
     */
    override fun indexOf(element: T): Int {
        var count = 0
        var node = head

        while (node != null) {
            if (node.value != element) {
                node = node.next
                count++
            } else {
                return count
            }
        }

        // nothing was found
        return -1
    }

    /**
     * Returns the index of the last occurrence of the specified element in the list, or -1 if the specified
     * element is not contained in the list.
     */
    override fun lastIndexOf(element: T): Int {
        var count = 0
        var node = head

        while (node != null) {
            if (node.value != element) {
                node = node.next
                count++
            }
        }

        // nothing was found
        return if (count == 0) -1 else count
    }

    /**
     * Returns a view of the portion of this list between the specified [fromIndex] (inclusive) and [toIndex] (exclusive).
     * The returned list is backed by this list, so non-structural changes in the returned list are reflected in this list, and vice-versa.
     *
     * Structural changes in the base list make the behavior of the view undefined.
     */
    override fun subList(fromIndex: Int, toIndex: Int): List<T> {
        throw NotImplementedError("This functionality is not implemented")
    }
}
