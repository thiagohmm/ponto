import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { Bars } from "../src/ui/Bars";

afterEach(cleanup);

const bar = (value: number, label: string) => ({ value, label, color: "#007aff" });

it("desenha uma coluna por barra e rótulos só nas posições do intervalo", () => {
  const { container } = render(<Bars label="minutos" every={2} bars={[bar(60, "1"), bar(120, "2"), bar(0, "3")]} />);
  expect(container.querySelectorAll(".bars-col")).toHaveLength(3);
  // every=2: "1", "3"
  expect([...container.querySelectorAll(".bars-x")].map((e) => e.textContent)).toEqual(["1", "3"]);
});

it("mantém a barra zerada com 3% de altura para continuar clicável", () => {
  const { container } = render(<Bars label="minutos" bars={[bar(60, "1"), bar(0, "2")]} />);
  const zero = container.querySelectorAll<HTMLElement>(".bars-bar")[1];
  expect(zero.style.height).toBe("3%");
  expect(parseFloat(zero.style.top)).toBeCloseTo(97, 5);
});

it("cresce para baixo quando o valor é negativo, como no banco de horas", () => {
  const { container } = render(<Bars label="saldo" zero bars={[bar(-60, "01/25")]} />);
  const down = container.querySelector<HTMLElement>(".bars-bar")!;
  expect(down.style.top).toBe("0%");
  expect(parseFloat(down.style.height)).toBe(100);
  expect(container.querySelector(".bars-zero")).not.toBeNull();
});

it("mostra a linha da meta e o tooltip no hover", () => {
  const { container } = render(<Bars label="minutos" mark={90} bars={[bar(60, "9"), bar(120, "10")]} />);
  expect(container.querySelector(".bars-mark")).not.toBeNull();
  fireEvent.mouseOver(container.querySelectorAll(".bars-col")[1]);
  expect(screen.getByText("10 · 120")).toBeTruthy();
});

it("liga o clique da coluna ao índice da barra", () => {
  const onPick = vi.fn();
  const { container } = render(<Bars label="minutos" onPick={onPick} bars={[bar(60, "9"), bar(120, "10")]} />);
  fireEvent.click(container.querySelectorAll(".bars-col")[1]);
  expect(onPick).toHaveBeenCalledWith(1);
});
