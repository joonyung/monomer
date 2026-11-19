package cc.monomer.control.pages.inbound.rest.pages

data class PageContentDocumentResponse(
    val title: String,
    val version: PageContentVersionResponse,
    val attachmentUrl: String,
)
