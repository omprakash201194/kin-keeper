export type Category = {
  id: string
  familyId?: string
  name: string
  parentId?: string | null
  isDefault?: boolean
  default?: boolean
}

export type CategoryWithDepth = Category & { depth: number }

function isDefaultFlag(c: Category): boolean {
  return c.isDefault === true || c.default === true
}

/**
 * Produce a flat, pre-order list of categories where children follow their
 * parent with increasing depth. Used for both the tree view and the indented
 * dropdown in upload pickers.
 */
export function flattenCategoryTree(categories: Category[]): CategoryWithDepth[] {
  const byParent = new Map<string | null, Category[]>()
  for (const c of categories) {
    const key = c.parentId ?? null
    const list = byParent.get(key) ?? []
    list.push(c)
    byParent.set(key, list)
  }
  for (const list of byParent.values()) {
    list.sort((a, b) => {
      const ad = isDefaultFlag(a) ? 0 : 1
      const bd = isDefaultFlag(b) ? 0 : 1
      if (ad !== bd) return ad - bd
      return a.name.localeCompare(b.name)
    })
  }

  const out: CategoryWithDepth[] = []
  function walk(parentId: string | null, depth: number) {
    const children = byParent.get(parentId) ?? []
    for (const c of children) {
      out.push({ ...c, depth })
      walk(c.id, depth + 1)
    }
  }
  walk(null, 0)
  return out
}

export function categoryLabelWithAncestors(
  categories: Category[],
  categoryId: string,
): string {
  const byId = new Map(categories.map((c) => [c.id, c]))
  const parts: string[] = []
  let current = byId.get(categoryId)
  const seen = new Set<string>()
  while (current && !seen.has(current.id)) {
    seen.add(current.id)
    parts.unshift(current.name)
    current = current.parentId ? byId.get(current.parentId) : undefined
  }
  return parts.join(' › ')
}

export function isDefaultCategory(c: Category): boolean {
  return isDefaultFlag(c)
}
