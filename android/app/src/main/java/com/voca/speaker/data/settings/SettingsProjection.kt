package com.voca.speaker.data.settings

import com.voca.speaker.data.analysis.TextProvider
import com.voca.speaker.data.analysis.TextProviderCatalog
import com.voca.speaker.domain.AppSettings
import com.voca.speaker.domain.AssessorProvider
import com.voca.speaker.domain.LiveVendor
import com.voca.speaker.domain.ProviderGroup
import com.voca.speaker.domain.ProviderLine
import com.voca.speaker.domain.key

/**
 * 设置页供应商列表投影：把三组供应商的配置状态与展示摘要收口在这一层，
 * 业务层与界面只消费 [ProviderLine]，不直接枚举供应商目录。
 */
object SettingsProjection {

    fun providerLines(settings: AppSettings): List<ProviderLine> {
        val live = settings.live
        val realtime = LiveVendor.entries.map { vendor ->
            ProviderLine(
                group = ProviderGroup.Realtime,
                key = vendor.key,
                name = vendor.label,
                detail = live.tuningOf(vendor).model,
                configured = live.ready(vendor),
                active = live.provider == vendor,
            )
        }
        val assessor = settings.assessor
        val assessors = AssessorProvider.entries.filter { it != AssessorProvider.None }.map { vendor ->
            ProviderLine(
                group = ProviderGroup.Assessor,
                key = vendor.key,
                name = vendor.label,
                detail = vendor.detail(assessor),
                configured = assessor.ready(vendor),
                active = assessor.resolvedProvider == vendor,
            )
        }
        val analysis = settings.analysis
        val text = TextProviderCatalog.all.map { provider ->
            val own = analysis.of(provider.id)
            ProviderLine(
                group = ProviderGroup.Analysis,
                key = provider.id,
                name = provider.label,
                detail = own.model.ifBlank { hostOf(provider) },
                configured = own.ready,
                active = analysis.provider == provider.id,
            )
        }
        return realtime + assessors + text
    }

    private fun hostOf(provider: TextProvider): String =
        provider.endpoint.substringAfter("://").substringBefore('/').ifBlank { "未填写" }
}
