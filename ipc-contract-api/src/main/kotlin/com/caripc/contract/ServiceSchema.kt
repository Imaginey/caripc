package com.caripc.contract

data class ServiceSchema(
    val contractId: String,
    val major: Int,
    val minor: Int,
    val properties: List<PropertyKey<*>> = emptyList(),
    val events: List<EventKey<*>> = emptyList(),
    val commands: List<CommandKey<*, *>> = emptyList()
) {
    init {
        require(contractId.isNotBlank()) { "contractId must not be blank" }
        require(major >= 1) { "major must be >= 1" }
        require(minor >= 0) { "minor must be >= 0" }
    }

    private val propertyMap = properties.associateBy { it.id }
    private val eventMap = events.associateBy { it.id }
    private val commandMap = commands.associateBy { it.id }

    fun findProperty(id: String): PropertyKey<*>? = propertyMap[id]
    fun findEvent(id: String): EventKey<*>? = eventMap[id]
    fun findCommand(id: String): CommandKey<*, *>? = commandMap[id]

    class Builder(val contractId: String, val major: Int, val minor: Int) {
        private val properties = mutableListOf<PropertyKey<*>>()
        private val events = mutableListOf<EventKey<*>>()
        private val commands = mutableListOf<CommandKey<*, *>>()

        fun addProperty(key: PropertyKey<*>) = apply { properties.add(key) }
        fun addEvent(key: EventKey<*>) = apply { events.add(key) }
        fun addCommand(key: CommandKey<*, *>) = apply { commands.add(key) }

        fun build() = ServiceSchema(contractId, major, minor, properties, events, commands)
    }

    companion object {
        @JvmStatic
        fun builder(contractId: String, major: Int, minor: Int) = Builder(contractId, major, minor)
    }
}
