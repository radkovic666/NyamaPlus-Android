package fun.nyama.tv;

import java.util.List;

public final class CatalogPage {
    public final List<CatalogItem> items;
    public final int total;
    public final int page;
    public final int totalPages;

    public CatalogPage(List<CatalogItem> items, int total, int page, int totalPages) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.totalPages = totalPages;
    }
}
