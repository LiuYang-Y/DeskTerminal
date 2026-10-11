package com.qingke.desk.connection

import com.qingke.desk.projection.api.AvailabilityResult
import com.qingke.desk.projection.api.ProjectionKind
import com.qingke.desk.projection.api.ProjectionSessionFactory
import com.qingke.desk.projection.api.ProjectionSessionRegistry

/**
 * 工厂注册表。
 *
 * 新增一种投屏协议 = 新增一个 Gradle module + 一个 Factory，
 * 然后在 :app 的图里加进列表。本类与 :shell 都不需要改。
 */
class ProjectionSessionRegistryImpl(
    factories: List<ProjectionSessionFactory>,
) : ProjectionSessionRegistry {

    private val items: List<ProjectionSessionFactory> = factories.toList()

    override fun all(): List<ProjectionSessionFactory> = items

    override fun find(kind: ProjectionKind): ProjectionSessionFactory? =
        items.firstOrNull { it.kind == kind }

    /**
     * 首选工厂。当前就是列表中第一个可用的。
     *
     * TODO(step-2): 接了多个实现后，这里要改成按"已配对设备"或用户手动选择来定优先级。
     */
    override fun preferred(): ProjectionSessionFactory? =
        items.firstOrNull { it.checkAvailability() is AvailabilityResult.Ready }
}
