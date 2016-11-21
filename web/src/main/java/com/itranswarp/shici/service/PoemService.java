package com.itranswarp.shici.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityNotFoundException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.itranswarp.shici.bean.CategoryBean;
import com.itranswarp.shici.bean.CategoryPoemBean;
import com.itranswarp.shici.bean.FeaturedBean;
import com.itranswarp.shici.bean.PoemBean;
import com.itranswarp.shici.bean.PoetBean;
import com.itranswarp.shici.exception.APIArgumentException;
import com.itranswarp.shici.json.LocalDateDeserializer;
import com.itranswarp.shici.json.LocalDateSerializer;
import com.itranswarp.shici.model.BaseEntity;
import com.itranswarp.shici.model.Category;
import com.itranswarp.shici.model.CategoryPoem;
import com.itranswarp.shici.model.Dynasty;
import com.itranswarp.shici.model.FeaturedPoem;
import com.itranswarp.shici.model.Poem;
import com.itranswarp.shici.model.Poet;
import com.itranswarp.shici.model.Resource;
import com.itranswarp.shici.util.HttpUtil;
import com.itranswarp.shici.util.IdUtils;
import com.itranswarp.warpdb.PagedResults;
import com.itranswarp.wxapi.util.MapUtil;

@RestController
@Transactional
public class PoemService extends AbstractService {

	@Autowired
	HanziService hanziService;

	// dynasty ////////////////////////////////////////////////////////////////

	@RequestMapping(value = "/api/dynasties", method = RequestMethod.GET)
	public Map<String, List<Dynasty>> restGetDynasties() {
		return MapUtil.createMap("results", getDynasties());
	}

	@RequestMapping(value = "/api/dynasties/{id}", method = RequestMethod.GET)
	public Dynasty restGetDynasty(@PathVariable("id") String dynastyId) {
		return getDynasty(dynastyId);
	}

	public List<Dynasty> getDynasties() {
		return warpdb.from(Dynasty.class).orderBy("displayOrder").list();
	}

	public Dynasty getDynasty(String dynastyId) {
		for (Dynasty d : getDynasties()) {
			if (d.id.equals(dynastyId)) {
				return d;
			}
		}
		throw new EntityNotFoundException("Dynasty");
	}

	// poet ///////////////////////////////////////////////////////////////////

	@RequestMapping(value = "/api/dynasties/{id}/poets", method = RequestMethod.GET)
	public Map<String, List<Poet>> restGetPoets(@PathVariable("id") String dynastyId) {
		return MapUtil.createMap("results", getPoets(dynastyId));
	}

	public List<Poet> getPoets(String dynastyId) {
		return warpdb.from(Poet.class).where("dynastyId=?", dynastyId).orderBy("name").list();
	}

	@RequestMapping(value = "/api/poets/{id}", method = RequestMethod.GET)
	public Poet getPoet(@PathVariable("id") String poetId) {
		return warpdb.get(Poet.class, poetId);
	}

	@RequestMapping(value = "/api/poets", method = RequestMethod.POST)
	public Poet createPoet(@RequestBody PoetBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		getDynasty(bean.dynastyId);
		// create:
		Poet poet = new Poet();
		copyToPoet(poet, bean);
		warpdb.save(poet);
		return poet;
	}

	@RequestMapping(value = "/api/poets/{id}", method = RequestMethod.POST)
	public Poet updatePoet(@PathVariable("id") String poetId, @RequestBody PoetBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		getDynasty(bean.dynastyId);
		// update:
		Poet poet = getPoet(poetId);
		copyToPoet(poet, bean);
		warpdb.update(poet);
		return poet;
	}

	private void copyToPoet(Poet poet, PoetBean bean) {
		poet.dynastyId = bean.dynastyId;
		poet.name = hanziService.toChs(bean.name);
		poet.nameCht = hanziService.toCht(bean.name);
		poet.description = hanziService.toChs(bean.description);
		poet.descriptionCht = hanziService.toCht(bean.description);
		poet.birth = bean.birth;
		poet.death = bean.death;
	}

	@RequestMapping(value = "/api/poets/{id}", method = RequestMethod.DELETE)
	public void deletePoet(@PathVariable("id") String poetId) {
		// check:
		assertEditorRole();
		Poet poet = getPoet(poetId);
		// check:
		if (warpdb.from(Poem.class).where("poetId=?", poetId).limit(1).list().size() > 0) {
			throw new DataIntegrityViolationException("Cannot remove poet who has poems.");
		}
		// delete:
		warpdb.remove(poet);
	}

	// poem ///////////////////////////////////////////////////////////////////

	@RequestMapping(value = "/api/poems/{id}", method = RequestMethod.GET)
	public Poem getPoem(@PathVariable("id") String poemId) {
		return warpdb.get(Poem.class, poemId);
	}

	@RequestMapping(value = "/api/poets/{id}/poems", method = RequestMethod.GET)
	public PagedResults<Poem> getPoems(@PathVariable("id") String poetId,
			@RequestParam(value = "page", defaultValue = "1") int pageIndex) {
		return warpdb.from(Poem.class).where("poetId=?", poetId).orderBy("name").list(pageIndex, 20);
	}

	@RequestMapping(value = "/api/poems", method = RequestMethod.POST)
	public Poem createPoem(@RequestBody PoemBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		Poet poet = getPoet(bean.poetId);
		Poem poem = new Poem();
		poem.id = IdUtils.next();
		// create image:
		if (bean.imageData != null) {
			Resource resource = createResource(poem, "cover", ".jpg", bean.imageData);
			poem.imageId = resource.id;
		} else {
			poem.imageId = "";
		}
		// create:
		copyToPoem(poem, poet, bean);
		warpdb.save(poem);
		updatePoemCountOfPoet(bean.poetId);
		return poem;
	}

	@RequestMapping(value = "/api/poems/{id}", method = RequestMethod.POST)
	public Poem updatePoem(@PathVariable("id") String poemId, @RequestBody PoemBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		Poet poet = getPoet(bean.poetId);
		Poem poem = getPoem(poemId);
		String oldPoetId = poem.poetId;
		String newPoetId = bean.poetId;
		copyToPoem(poem, poet, bean);
		// update:
		warpdb.update(poem);
		if (!oldPoetId.equals(newPoetId)) {
			updatePoemCountOfPoet(oldPoetId, newPoetId);
		}
		// update image:
		if (bean.imageData != null) {
			String oldResourceId = poem.imageId;
			Resource resource = createResource(poem, "cover", ".jpg", bean.imageData);
			poem.imageId = resource.id;
			warpdb.updateProperties(poem, "imageId");
			if (!oldResourceId.isEmpty()) {
				deleteResource(oldResourceId);
			}
		}
		return poem;
	}

	private void copyToPoem(Poem poem, Poet poet, PoemBean bean) {
		poem.dynastyId = poet.dynastyId;
		poem.poetId = poet.id;
		poem.poetName = poet.name;
		poem.poetNameCht = poet.nameCht;
		poem.form = bean.form;
		poem.tags = bean.tags;
		poem.name = hanziService.toChs(bean.name);
		poem.nameCht = hanziService.toCht(bean.name);
		poem.content = hanziService.toChs(bean.content);
		poem.contentCht = hanziService.toCht(bean.content);
		poem.appreciation = hanziService.toChs(bean.appreciation);
		poem.appreciationCht = hanziService.toCht(bean.appreciation);
	}

	@RequestMapping(value = "/api/poems/{id}/delete", method = RequestMethod.POST)
	public void deletePoem(@PathVariable("id") String poemId) {
		// check:
		assertEditorRole();
		Poem poem = getPoem(poemId);
		// delete:
		warpdb.remove(poem);
		updatePoemCountOfPoet(poem.poetId);
	}

	private void updatePoemCountOfPoet(String... poetIds) {
		for (String poetId : poetIds) {
			warpdb.update("update Poet set poemCount=(select count(id) from Poem where poetId=?) where id=?", poetId,
					poetId);
		}
	}

	// Resource ///////////////////////////////////////////////////////////////

	public Resource getResource(String resourceId) {
		return warpdb.get(Resource.class, resourceId);
	}

	public Resource createResource(BaseEntity ref, String name, String ext, String base64Data) {
		Resource resource = new Resource();
		resource.meta = "";
		resource.mime = HttpUtil.guessContentType(ext);
		resource.name = name;
		resource.refId = ref.id;
		resource.refType = ref.getClass().getSimpleName();
		resource.size = getSizeOfBase64String(base64Data);
		resource.data = base64Data;
		warpdb.save(resource);
		return resource;
	}

	public void deleteResource(String resourceId) {
		Resource resource = new Resource();
		resource.id = resourceId;
		warpdb.remove(resource);
	}

	int getSizeOfBase64String(String base64Data) {
		int n = base64Data.length();
		if (base64Data.endsWith("==")) {
			n = n - 2;
		} else if (base64Data.endsWith("=")) {
			n = n - 1;
		}
		int seg = (n / 4) * 3;
		int mod = n % 4;
		if (mod == 3) {
			seg = seg + 2;
		} else if (mod == 2) {
			seg = seg + 1;
		}
		return seg;
	}

	// category ///////////////////////////////////////////////////////////////

	@RequestMapping(value = "/api/categories", method = RequestMethod.GET)
	public Map<String, List<Category>> restGetCategories() {
		return MapUtil.createMap("results", getCategories());
	}

	@RequestMapping(value = "/api/categories/{id}", method = RequestMethod.GET)
	public Category restGetCategory(@PathVariable("id") String categoryId) {
		return getCategory(categoryId);
	}

	public List<Category> getCategories() {
		return warpdb.from(Category.class).orderBy("displayOrder").list();
	}

	public Category getCategory(String categoryId) {
		for (Category c : getCategories()) {
			if (c.id.equals(categoryId)) {
				return c;
			}
		}
		throw new EntityNotFoundException("Category");
	}

	@RequestMapping(value = "/api/categories", method = RequestMethod.POST)
	public Category createCategory(@RequestBody CategoryBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		// create:
		Category category = new Category();
		category.name = bean.name;
		category.nameCht = hanziService.toCht(bean.name);
		category.description = bean.description;
		category.descriptionCht = hanziService.toCht(bean.description);
		long max = -1;
		for (Category c : getCategories()) {
			max = c.displayOrder;
		}
		category.displayOrder = max + 1;
		warpdb.save(category);
		return category;
	}

	@RequestMapping(value = "/api/categories/{id}", method = RequestMethod.POST)
	public Category updateCategory(@PathVariable("id") String categoryId, @RequestBody CategoryBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		// update:
		Category category = getCategory(categoryId);
		category.name = bean.name;
		category.nameCht = hanziService.toCht(bean.name);
		category.description = bean.description;
		category.descriptionCht = hanziService.toCht(bean.description);
		warpdb.update(category);
		return category;
	}

	public void deleteCategory(String categoryId) {
		// check:
		assertEditorRole();
		Category category = getCategory(categoryId);
		warpdb.remove(category);
	}

	@RequestMapping(value = "/api/categories/{id}/poems", method = RequestMethod.GET)
	public Map<String, List<TheCategoryPoem>> restGetPoemsOfCategory(@PathVariable("id") String categoryId) {
		List<TheCategoryPoem> list = getPoemsOfCategory(categoryId);
		return MapUtil.createMap("results", list);
	}

	public List<TheCategoryPoem> getPoemsOfCategory(String categoryId) {
		Category category = getCategory(categoryId);
		List<CategoryPoem> cps = warpdb.from(CategoryPoem.class).where("categoryId=?", category.id)
				.orderBy("displayOrder").list();
		List<TheCategoryPoem> list = new ArrayList<>();
		if (cps.isEmpty()) {
			return list;
		}
		TheCategoryPoem tcp = null;
		for (CategoryPoem cp : cps) {
			Poem poem = warpdb.get(Poem.class, cp.poemId);
			if (tcp == null) {
				// start new section:
				tcp = new TheCategoryPoem();
				tcp.sectionName = cp.sectionName;
				tcp.sectionNameCht = cp.sectionNameCht;
				tcp.poems = new ArrayList<>(20);
				tcp.poems.add(poem);
			} else {
				if (tcp.sectionName.equals(cp.sectionName)) {
					// continue section:
					tcp.poems.add(poem);
				} else {
					// start new section:
					list.add(tcp);
					tcp = new TheCategoryPoem();
					tcp.sectionName = cp.sectionName;
					tcp.sectionNameCht = cp.sectionNameCht;
					tcp.poems = new ArrayList<>(20);
					tcp.poems.add(poem);
				}
			}
		}
		list.add(tcp);
		return list;
	}

	@RequestMapping(value = "/api/categories/{id}/poems", method = RequestMethod.POST)
	public void updatePoemsOfCategory(@PathVariable("id") String categoryId,
			@RequestBody List<CategoryPoemBean> beans) {
		// check:
		assertEditorRole();
		if (beans == null) {
			throw new APIArgumentException("body is empty");
		}
		for (CategoryPoemBean bean : beans) {
			bean.validate();
		}
		Category category = getCategory(categoryId);
		// set:
		warpdb.update("delete from CategoryPoem where categoryId=?", categoryId);
		long n = 0;
		for (CategoryPoemBean bean : beans) {
			List<CategoryPoem> list = new ArrayList<>(bean.ids.size());
			for (String poemId : bean.ids) {
				CategoryPoem cp = new CategoryPoem();
				cp.sectionName = bean.sectionName;
				cp.sectionNameCht = hanziService.toCht(bean.sectionName);
				cp.categoryId = categoryId;
				cp.poemId = poemId;
				cp.displayOrder = n;
				list.add(cp);
				n++;
			}
			warpdb.save(list.toArray(new CategoryPoem[list.size()]));
		}
		warpdb.update(category); // update version!
	}

	// featured ///////////////////////////////////////////////////////////////

	@RequestMapping(value = "/api/featured/poem", method = RequestMethod.GET)
	public Poem restGetFeaturedPoem() {
		return getFeaturedPoem(LocalDate.now());
	}

	public Poem getFeaturedPoem(LocalDate targetDate) {
		FeaturedPoem fp = warpdb.from(FeaturedPoem.class).where("pubDate<=?", targetDate).orderBy("pubDate desc")
				.first();
		if (fp == null) {
			throw new EntityNotFoundException(Poem.class.getSimpleName());
		}
		return getPoem(fp.poemId);
	}

	@RequestMapping(value = "/api/featured/poems", method = RequestMethod.GET)
	public Map<String, List<TheFeaturedPoem>> restGetFeaturedPoems() {
		return MapUtil.createMap("results", getFeaturedPoems());
	}

	public List<TheFeaturedPoem> getFeaturedPoems() {
		List<FeaturedPoem> fps = warpdb.list("select * from FeaturedPoem order by pubDate desc");
		List<TheFeaturedPoem> tfps = new ArrayList<>(fps.size());
		for (FeaturedPoem fp : fps) {
			TheFeaturedPoem tfp = new TheFeaturedPoem();
			tfp.pubDate = fp.pubDate;
			tfp.poem = getPoem(fp.poemId);
			tfps.add(tfp);
		}
		return tfps;
	}

	@RequestMapping(value = "/api/featured", method = RequestMethod.POST)
	public void setPoemAsFeatured(@RequestBody FeaturedBean bean) {
		// check:
		assertEditorRole();
		bean.validate();
		Poem poem = getPoem(bean.poemId);
		if (poem.imageId.isEmpty()) {
			throw new APIArgumentException("poemId", "Poem does not have image.");
		}
		FeaturedPoem fp = warpdb.from(FeaturedPoem.class).where("poemId=?", poem.id).first();
		if (fp != null) {
			throw new APIArgumentException("poemId", "Poem already set featured.");
		}
		fp = new FeaturedPoem();
		fp.poemId = bean.poemId;
		fp.pubDate = bean.pubDate;
		warpdb.save(fp);
	}

	@RequestMapping(value = "/api/featured/{poemId}/delete", method = RequestMethod.POST)
	public void setPoemAsUnfeatured(@PathVariable("poemId") String poemId) {
		// check:
		assertEditorRole();
		Poem poem = getPoem(poemId);
		FeaturedPoem fp = warpdb.from(FeaturedPoem.class).where("poemId=?", poem.id).first();
		if (fp == null) {
			throw new APIArgumentException("poemId", "Poem is not featured.");
		}
		warpdb.remove(fp);
	}

	public static class TheCategoryPoem {
		public String sectionName;
		public String sectionNameCht;
		public List<Poem> poems;
	}

	public static class TheFeaturedPoem {
		public Poem poem;

		@JsonSerialize(using = LocalDateSerializer.class)
		@JsonDeserialize(using = LocalDateDeserializer.class)
		public LocalDate pubDate;
	}
}
