package ponto.app;

import ponto.infra.CompaniesStore;
import ponto.model.Company;

import java.util.List;

/** Application API for workplaces (empresas). */
public final class CompanyApp {
    private final CompaniesStore store;

    CompanyApp(CompaniesStore store) { this.store = store; }

    CompaniesStore store() { return store; }

    public Company active() { return store.active(); }
    public List<Company> list() { return store.list(); }
    public Company byId(int id) { return store.byId(id); }
    public Company create(String name, String off, String daily) { return store.create(name, off, daily); }
    public Company update(int id, String name, String off, String daily) {
        return store.update(id, name, off, daily);
    }
    public void delete(int id) { store.delete(id); }
    public void activate(int id) { store.activate(id); }
}
